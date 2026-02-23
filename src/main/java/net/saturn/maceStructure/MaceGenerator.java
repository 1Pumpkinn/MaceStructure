package net.saturn.maceStructure;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Biome;
import org.bukkit.plugin.Plugin;
import org.bukkit.structure.Structure;
import org.bukkit.util.Vector;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.io.InputStream;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class MaceGenerator {
    private final Plugin plugin;
    private final Random random = new Random();

    /** How many times we will retry after relaxing flatness constraints. */
    private static final int MAX_RELAX_ROUNDS = 3;

    /**
     * Horizontal radius (in blocks) around each footprint column to scan for water/lava.
     * Prevents generation on coastal cliffs that technically aren't "underwater" but
     * are right on the water's edge — exactly the bad-looking case in the screenshot.
     */
    private static final int WATER_PROXIMITY_RADIUS = 8;

    MaceGenerator(Plugin plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------
    // Public entry points
    // -------------------------------------------------------------------------

    void generateIfNeeded() {
        generateIfNeeded(false, false, null);
    }

    void generateIfNeeded(boolean allowChunkLoad, boolean relaxFlatness, World targetWorld) {
        if (isAlreadyGenerated()) return;
        startGenerationRound(allowChunkLoad, relaxFlatness ? 1 : 0, targetWorld, null, null);
    }

    // -------------------------------------------------------------------------
    // Core generation logic
    // -------------------------------------------------------------------------

    private boolean isAlreadyGenerated() {
        FileConfiguration cfg = plugin.getConfig();
        if (cfg.getBoolean("generated.enabled", false)) {
            plugin.getLogger().info("Mace structure already recorded at "
                    + cfg.getString("generated.world") + " ("
                    + cfg.getInt("generated.x") + ", "
                    + cfg.getInt("generated.y") + ", "
                    + cfg.getInt("generated.z") + ")");
            return true;
        }
        return false;
    }

    /**
     * @param relaxRound 0 = strict, 1+ = progressively relaxed constraints
     * @param reuseX/reuseZ non-null means retry the same XZ after chunk load
     */
    private void startGenerationRound(boolean allowChunkLoad, int relaxRound,
                                      World targetWorld, Integer reuseX, Integer reuseZ) {
        FileConfiguration cfg = plugin.getConfig();

        String worldName = Objects.requireNonNullElse(cfg.getString("world.name"),
                Bukkit.getWorlds().getFirst().getName());
        World world = (targetWorld != null) ? targetWorld : Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("World not found: " + worldName);
            return;
        }

        Structure structure = loadStructure();
        if (structure == null) {
            plugin.getLogger().warning("Could not load structure. Check structure.key or place file at "
                    + new File(plugin.getDataFolder(),
                    Objects.requireNonNullElse(cfg.getString("structure.file"), "mace.nbt")).getPath());
            return;
        }

        // --- Base config values ---
        int minDist = Math.max(0, cfg.getInt("distance.min", 2500));
        int maxDist = Math.max(minDist + 1, cfg.getInt("distance.max", 4000));
        int attempts = Math.max(16, cfg.getInt("attempts", 128));
        int yOffset = cfg.getInt("placement.yOffset", 0);
        int maxVarianceCfg = Math.max(0, cfg.getInt("placement.maxVariance", 1));
        double minSolidRatioCfg = clamp(cfg.getDouble("placement.minSolidRatio", 0.9), 0, 1);
        boolean avoidLiquids = cfg.getBoolean("placement.avoidLiquids", true);
        boolean requireFlat = cfg.getBoolean("placement.requireFlat", true);
        double minAllowedBiomeRatioCfg = clamp(cfg.getDouble("placement.minAllowedBiomeRatio", 0.8), 0, 1);

        // --- Relaxed overrides ---
        // NOTE: liquid/water-proximity rejection is NEVER relaxed — it is always a hard reject.
        boolean effectiveRequireFlat = (relaxRound >= 1) ? false : requireFlat;
        int maxVariance = Math.min(maxVarianceCfg + relaxRound * 2, maxVarianceCfg + 4);
        double minSolidRatio = Math.max(0, minSolidRatioCfg - relaxRound * 0.15);
        double minAllowedBiomeRatio = (relaxRound >= 1) ? 0.0 : minAllowedBiomeRatioCfg;

        Set<Biome> allowedBiomes = loadAllowedBiomes(cfg);

        // --- World border clamping ---
        WorldBorder border = world.getWorldBorder();
        double half = border.getSize() / 2.0;
        Vector size = structure.getSize();
        double footprint = Math.max(size.getX(), size.getZ());
        int borderLimit = (int) Math.max(0, Math.floor(half - (footprint / 2.0) - 4.0));
        if (borderLimit < maxDist) maxDist = Math.max(minDist, borderLimit);
        if (minDist > maxDist) minDist = maxDist;

        int tries = (reuseX != null && reuseZ != null) ? 1 : attempts;

        // Rejection counters
        int rejectUnloaded = 0, rejectBiome = 0, rejectFlat = 0, rejectBorder = 0, rejectWater = 0, rejectVolume = 0;

        // Max fraction of the structure volume that may be occupied by solid blocks.
        // At relaxRound 0 this is 0 (fully clear required). Each round allows a little more.
        double maxOccupiedRatio = clamp(relaxRound * 0.05, 0, 0.15);

        for (int i = 0; i < tries; i++) {
            int x, z;
            if (reuseX != null && reuseZ != null) {
                x = reuseX;
                z = reuseZ;
            } else {
                int r = random.nextInt(maxDist - minDist + 1) + minDist;
                double t = random.nextDouble() * Math.PI * 2.0;
                Location c = border.getCenter();
                x = (int) Math.round(c.getBlockX() + Math.cos(t) * r);
                z = (int) Math.round(c.getBlockZ() + Math.sin(t) * r);
            }

            // --- Chunk availability ---
            if (!chunksAvailableForBounds(world, x, z, size)) {
                if (!allowChunkLoad) {
                    rejectUnloaded++;
                    continue;
                }
                final int fx = x, fz = z;
                final int nextRelax = relaxRound;
                final World fw = world;
                loadChunksForBoundsAsync(world, x, z, size).thenRun(() ->
                        Bukkit.getScheduler().runTask(plugin, () ->
                                startGenerationRound(false, nextRelax, fw, fx, fz)
                        )
                );
                return;
            }

            // --- Footprint analysis ---
            FlatCheck check = analyzeFootprint(world, x, z, size, avoidLiquids, allowedBiomes);

            // Hard reject: any water/lava in or near the footprint — never relaxed
            if (avoidLiquids && check.hasLiquid) {
                rejectWater++;
                continue;
            }

            if (check.allowedBiomeRatio < minAllowedBiomeRatio) {
                rejectBiome++;
                continue;
            }

            if (effectiveRequireFlat) {
                if (check.variance > maxVariance || check.solidRatio < minSolidRatio) {
                    rejectFlat++;
                    continue;
                }
            }

            // --- Y placement ---
            int sy = (int) size.getY();
            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight();
            int yCandidate = (check.minGround + 1) + yOffset;
            int y = Math.min(Math.max(yCandidate, minY), maxY - sy);
            if (y < minY || y + sy > maxY) {
                rejectBorder++;
                continue;
            }
            if (!fitsInsideBorder(world, x, y, z, size)) {
                rejectBorder++;
                continue;
            }

            // --- Volume obstruction check ---
            // Reject if the 3D space the structure would occupy contains solid terrain blocks.
            // This prevents the structure from being buried inside a hill.
            VolumeCheck vol = checkVolumeIsClear(world, x, y, z, size);
            if (vol.occupiedRatio > maxOccupiedRatio) {
                rejectVolume++;
                continue;
            }

            // --- Place ---
            placeStructure(cfg, world, x, y, z, size, structure);
            return;
        }

        // --- Failed this round ---
        plugin.getLogger().warning(String.format(
                "Generation attempt (relaxRound=%d) failed after %d tries. "
                        + "Rejects: unloaded=%d biome=%d flatness=%d border=%d water/proximity=%d volume=%d",
                relaxRound, tries, rejectUnloaded, rejectBiome, rejectFlat, rejectBorder, rejectWater, rejectVolume));

        if (rejectUnloaded > 0 && !allowChunkLoad) {
            plugin.getLogger().info("Retrying with chunk loading enabled...");
            final World fw = world;
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    startGenerationRound(true, relaxRound, fw, null, null), 20L);
        } else if (relaxRound < MAX_RELAX_ROUNDS) {
            int next = relaxRound + 1;
            plugin.getLogger().info("Retrying with relaxed constraints (round " + next + "/" + MAX_RELAX_ROUNDS + ")...");
            final World fw = world;
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    startGenerationRound(allowChunkLoad, next, fw, null, null), 40L);
        } else {
            plugin.getLogger().severe(
                    "Mace structure could NOT be placed after all retry rounds. "
                            + "Check distance range vs world border size and biome settings.");
        }
    }

    // -------------------------------------------------------------------------
    // Placement helpers
    // -------------------------------------------------------------------------

    private void placeStructure(FileConfiguration cfg, World world,
                                int x, int y, int z, Vector size,
                                Structure structure) {
        Location loc = new Location(world, x, y, z);

        // No terrain modification — the location was already validated to be clear.
        structure.place(loc, true, StructureRotation.NONE,
                org.bukkit.block.structure.Mirror.NONE, -1, 1.0f, random);

        cfg.set("generated.enabled", true);
        cfg.set("generated.world", world.getName());
        cfg.set("generated.x", x);
        cfg.set("generated.y", y);
        cfg.set("generated.z", z);
        plugin.saveConfig();
        plugin.getLogger().info("Mace structure placed at "
                + world.getName() + " (" + x + ", " + y + ", " + z + ")");
    }

    // -------------------------------------------------------------------------
    // Footprint analysis
    // -------------------------------------------------------------------------

    private static boolean isNonGround(Material m) {
        return m == Material.AIR
                || m == Material.CAVE_AIR
                || m == Material.VOID_AIR
                || m == Material.WATER
                || m == Material.LAVA
                || m == Material.KELP
                || m == Material.KELP_PLANT
                || m == Material.SEAGRASS
                || m == Material.TALL_SEAGRASS
                || m == Material.SHORT_GRASS
                || m == Material.TALL_GRASS
                || m == Material.FERN
                || m == Material.LARGE_FERN
                || m == Material.DEAD_BUSH
                || m == Material.SNOW
                || m == Material.CACTUS
                || m == Material.VINE
                || m == Material.SUGAR_CANE
                || Tag.LOGS.isTagged(m)
                || Tag.LEAVES.isTagged(m)
                || Tag.SAPLINGS.isTagged(m)
                || Tag.FLOWERS.isTagged(m);
    }

    private int findTrueGroundY(World world, int x, int z) {
        int startY = world.getHighestBlockYAt(x, z);
        int minY = world.getMinHeight();
        for (int y = startY; y >= minY; y--) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (!isNonGround(m)) {
                return y;
            }
        }
        return minY;
    }

    /**
     * Returns true if the column at (x, z) has water or lava at or above groundY,
     * OR if any water/lava exists within WATER_PROXIMITY_RADIUS blocks horizontally.
     *
     * The proximity check is the key fix: it prevents spawning on coastal cliffs
     * where the footprint columns themselves are dry stone/grass but the structure
     * visually hangs right over the ocean edge (as seen in the screenshot).
     *
     * This check is NEVER relaxed regardless of relaxRound.
     */
    private boolean isSubmerged(World world, int x, int z, int groundY) {
        // 1. Direct column: water/lava above the true ground block
        int checkY = groundY + 1;
        int maxY = world.getMaxHeight();
        while (checkY < maxY) {
            Material m = world.getBlockAt(x, checkY, z).getType();
            if (m == Material.WATER || m == Material.LAVA) return true;
            if (m != Material.AIR && m != Material.CAVE_AIR && m != Material.VOID_AIR) break;
            checkY++;
        }

        // 2. Proximity: scan nearby columns in a square for water/lava near ground level.
        //    Check ±4 blocks vertically around groundY to catch ocean surfaces that are
        //    slightly higher or lower than the footprint's own ground.
        for (int dx = -WATER_PROXIMITY_RADIUS; dx <= WATER_PROXIMITY_RADIUS; dx++) {
            for (int dz = -WATER_PROXIMITY_RADIUS; dz <= WATER_PROXIMITY_RADIUS; dz++) {
                int nx = x + dx, nz = z + dz;
                for (int dy = -4; dy <= 4; dy++) {
                    int ny = groundY + dy;
                    if (ny < world.getMinHeight() || ny >= maxY) continue;
                    Material m = world.getBlockAt(nx, ny, nz).getType();
                    if (m == Material.WATER || m == Material.LAVA) return true;
                }
            }
        }
        return false;
    }

    private FlatCheck analyzeFootprint(World world, int originX, int originZ,
                                       Vector size, boolean avoidLiquids, Set<Biome> allowed) {
        int sx = (int) size.getX();
        int sz = (int) size.getZ();
        int minGround = Integer.MAX_VALUE;
        int maxGround = Integer.MIN_VALUE;
        int total = 0, solid = 0, allowedCount = 0;
        boolean hasLiquid = false;

        for (int dx = 0; dx < sx; dx++) {
            for (int dz = 0; dz < sz; dz++) {
                int gx = originX + dx;
                int gz = originZ + dz;

                int groundY = findTrueGroundY(world, gx, gz);

                if (groundY < minGround) minGround = groundY;
                if (groundY > maxGround) maxGround = groundY;

                // Water proximity check — hard reject, runs even if avoidLiquids=false
                // because the result is gated by avoidLiquids in the caller.
                if (avoidLiquids && !hasLiquid && isSubmerged(world, gx, gz, groundY)) {
                    hasLiquid = true;
                    // Don't break early — we still need min/max ground for variance.
                }

                Biome biome = world.getBiome(gx, groundY, gz);
                if (!allowed.isEmpty() && allowed.contains(biome)) allowedCount++;

                Material mat = world.getBlockAt(gx, groundY, gz).getType();
                total++;

                boolean isLiquidSurface = (mat == Material.WATER || mat == Material.LAVA);
                if (isLiquidSurface) {
                    hasLiquid = true;
                } else if (mat.isSolid()) {
                    solid++;
                }
            }
        }

        FlatCheck out = new FlatCheck();
        out.minGround = (minGround == Integer.MAX_VALUE) ? world.getMinHeight() : minGround;
        out.maxGround = (maxGround == Integer.MIN_VALUE) ? world.getMinHeight() : maxGround;
        out.variance = out.maxGround - out.minGround;
        out.solidRatio = total == 0 ? 0 : (solid / (double) total);
        out.hasLiquid = hasLiquid;
        out.allowedBiomeRatio = (allowed.isEmpty() || total == 0) ? 1.0 : (allowedCount / (double) total);
        return out;
    }

    // -------------------------------------------------------------------------
    // Biome loading
    // -------------------------------------------------------------------------

    private Set<Biome> loadAllowedBiomes(FileConfiguration cfg) {
        List<String> list = cfg.getStringList("placement.allowedBiomes");
        Set<Biome> result = new HashSet<>();

        if (list == null || list.isEmpty()) {
            // Default: only genuinely flat inland biomes. Forest/birch_forest removed
            // because they generate on coastal cliffs very frequently.
            for (String key : List.of("plains", "sunflower_plains", "savanna",
                    "savanna_plateau", "meadow", "snowy_plains")) {
                Biome b = resolveBiomeByKey("minecraft:" + key);
                if (b != null) result.add(b);
            }
            if (result.isEmpty()) plugin.getLogger().warning("No allowed biomes resolved; biome filter disabled.");
            return result;
        }

        for (String s : list) {
            Biome b = resolveBiomeByKey(s);
            if (b != null) {
                result.add(b);
            } else {
                plugin.getLogger().warning("Unknown biome in config: '" + s + "' — skipping.");
            }
        }

        if (result.isEmpty()) {
            plugin.getLogger().warning("No valid biomes resolved from config; biome filter disabled.");
        }
        return result;
    }

    private Biome resolveBiomeByKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();

        if (!s.contains(":")) {
            s = "minecraft:" + s.toLowerCase().replace(' ', '_');
        } else {
            s = s.toLowerCase();
        }

        NamespacedKey key = NamespacedKey.fromString(s);
        if (key == null) return null;

        try {
            var reg = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
            Biome b = reg.get(key);
            if (b != null) return b;
        } catch (Throwable ignored) {}

        try {
            return Biome.valueOf(s.replace("minecraft:", "").toUpperCase());
        } catch (IllegalArgumentException ignored) {}

        return null;
    }

    // -------------------------------------------------------------------------
    // Structure loading
    // -------------------------------------------------------------------------

    private Structure loadStructure() {
        FileConfiguration cfg = plugin.getConfig();

        String keyStr = cfg.getString("structure.key");
        if (keyStr != null && !keyStr.isBlank()) {
            NamespacedKey key = NamespacedKey.fromString(keyStr);
            if (key != null) {
                plugin.getLogger().info("Loading structure by key: " + key);
                Structure s = Bukkit.getStructureManager().loadStructure(key);
                if (s != null) return s;
                plugin.getLogger().warning("Structure key not found: " + key);
            }
        }

        String fileName = Objects.requireNonNullElse(cfg.getString("structure.file"), "mace.nbt");
        File file = new File(fileName);
        if (!file.isAbsolute()) {
            file = new File(plugin.getDataFolder(), fileName);
        }
        plugin.getLogger().info("Loading structure file: " + file.getPath());

        if (!file.exists()) {
            try (InputStream in = plugin.getResource(fileName)) {
                if (in != null) {
                    try {
                        return Bukkit.getStructureManager().loadStructure(in);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to load structure from resource: " + e.getMessage());
                        return null;
                    }
                }
            } catch (Exception ignored) {}
            return null;
        }

        try {
            return Bukkit.getStructureManager().loadStructure(file);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load structure file: " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Chunk helpers
    // -------------------------------------------------------------------------

    private boolean chunksAvailableForBounds(World world, int x, int z, Vector size) {
        int minCX = x >> 4,               minCZ = z >> 4;
        int maxCX = (x + (int)size.getX() - 1) >> 4;
        int maxCZ = (z + (int)size.getZ() - 1) >> 4;
        for (int cx = minCX; cx <= maxCX; cx++)
            for (int cz = minCZ; cz <= maxCZ; cz++)
                if (!world.isChunkLoaded(cx, cz)) return false;
        return true;
    }

    private CompletableFuture<Void> loadChunksForBoundsAsync(World world, int x, int z, Vector size) {
        int minCX = x >> 4,               minCZ = z >> 4;
        int maxCX = (x + (int)size.getX() - 1) >> 4;
        int maxCZ = (z + (int)size.getZ() - 1) >> 4;
        int count = (maxCX - minCX + 1) * (maxCZ - minCZ + 1);
        CompletableFuture<?>[] futures = new CompletableFuture[count];
        int idx = 0;
        for (int cx = minCX; cx <= maxCX; cx++)
            for (int cz = minCZ; cz <= maxCZ; cz++)
                futures[idx++] = world.getChunkAtAsync(cx, cz);
        return CompletableFuture.allOf(futures)
                .orTimeout(30, TimeUnit.SECONDS)
                .handle((v, ex) -> {
                    if (ex != null) plugin.getLogger().warning("Chunk load timed out: " + ex.getMessage());
                    return null;
                });
    }

    // -------------------------------------------------------------------------
    // World border / height helpers
    // -------------------------------------------------------------------------

    private boolean fitsInsideBorder(World world, int x, int y, int z, Vector size) {
        int sx = (int) size.getX(), sy = (int) size.getY(), sz = (int) size.getZ();
        if (y < world.getMinHeight() || y + sy > world.getMaxHeight()) return false;
        WorldBorder border = world.getWorldBorder();
        return border.isInside(new Location(world, x, y, z))
                && border.isInside(new Location(world, x + sx - 1, y, z))
                && border.isInside(new Location(world, x, y, z + sz - 1))
                && border.isInside(new Location(world, x + sx - 1, y, z + sz - 1));
    }

    // -------------------------------------------------------------------------
    // Volume obstruction check
    // -------------------------------------------------------------------------

    /**
     * Materials that are allowed to exist inside the structure's volume.
     * Everything else (stone, dirt, rock, etc.) is considered an obstruction.
     */
    private static boolean isVolumePassable(Material m) {
        return m == Material.AIR
                || m == Material.CAVE_AIR
                || m == Material.VOID_AIR
                || m == Material.WATER          // surface water already rejected earlier
                || m == Material.LAVA
                || m == Material.SHORT_GRASS
                || m == Material.TALL_GRASS
                || m == Material.FERN
                || m == Material.LARGE_FERN
                || m == Material.DEAD_BUSH
                || m == Material.SNOW
                || m == Material.CACTUS
                || m == Material.VINE
                || m == Material.SUGAR_CANE
                || m == Material.KELP
                || m == Material.KELP_PLANT
                || m == Material.SEAGRASS
                || m == Material.TALL_SEAGRASS
                || Tag.LOGS.isTagged(m)
                || Tag.LEAVES.isTagged(m)
                || Tag.SAPLINGS.isTagged(m)
                || Tag.FLOWERS.isTagged(m);
    }

    /**
     * Scans every block in the 3D bounding box the structure would occupy
     * (from y to y+sizeY-1) and counts how many are solid terrain.
     * Returns a VolumeCheck with the ratio of obstructed blocks to total blocks.
     */
    private VolumeCheck checkVolumeIsClear(World world, int originX, int baseY, int originZ, Vector size) {
        int sx = (int) size.getX();
        int sy = (int) size.getY();
        int sz = (int) size.getZ();
        int total = sx * sy * sz;
        int occupied = 0;

        for (int dx = 0; dx < sx; dx++) {
            for (int dz = 0; dz < sz; dz++) {
                for (int dy = 0; dy < sy; dy++) {
                    Material m = world.getBlockAt(originX + dx, baseY + dy, originZ + dz).getType();
                    if (!isVolumePassable(m)) {
                        occupied++;
                    }
                }
            }
        }

        VolumeCheck result = new VolumeCheck();
        result.occupiedRatio = total == 0 ? 0.0 : (occupied / (double) total);
        return result;
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private static double clamp(double v, double min, double max) {
        return Math.min(max, Math.max(min, v));
    }

    private static final class FlatCheck {
        int minGround, maxGround, variance;
        double solidRatio, allowedBiomeRatio;
        boolean hasLiquid;
    }

    private static final class VolumeCheck {
        double occupiedRatio;
    }
}