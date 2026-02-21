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

    MaceGenerator(Plugin plugin) {
        this.plugin = plugin;
    }

    void generateIfNeeded() {
        generateInternal(false, false, null);
    }

    void generateIfNeeded(boolean allowChunkLoad, boolean relaxFlatness) {
        generateInternal(allowChunkLoad, relaxFlatness, null);
    }

    void generateIfNeeded(boolean allowChunkLoad, boolean relaxFlatness, World targetWorld) {
        generateInternal(allowChunkLoad, relaxFlatness, targetWorld);
    }

    private void generateInternal(boolean allowChunkLoad, boolean relaxFlatness, World targetWorld) {
        generateInternal(allowChunkLoad, relaxFlatness, targetWorld, null, null);
    }

    private void generateInternal(boolean allowChunkLoad, boolean relaxFlatness, World targetWorld, Integer reuseX, Integer reuseZ) {
        FileConfiguration cfg = plugin.getConfig();
        if (cfg.getBoolean("generated.enabled", false)) {
            plugin.getLogger().info("Mace structure location already recorded at " + cfg.getString("generated.world") + " (" + cfg.getInt("generated.x") + ", " + cfg.getInt("generated.y") + ", " + cfg.getInt("generated.z") + ")");
            return;
        }
        String worldName = Objects.requireNonNullElse(cfg.getString("world.name"), Bukkit.getWorlds().getFirst().getName());
        World world = targetWorld != null ? targetWorld : Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("World not found: " + worldName);
            return;
        }
        Structure structure = loadStructure();
        if (structure == null) {
            plugin.getLogger().warning("Could not load structure. Check structure.key or place file at " + new File(plugin.getDataFolder(), Objects.requireNonNullElse(cfg.getString("structure.file"), "mace.nbt")).getPath());
            return;
        }
        int min = Math.max(0, cfg.getInt("distance.min", 2500));
        int max = Math.max(min + 1, cfg.getInt("distance.max", 4000));
        int attempts = Math.max(16, cfg.getInt("attempts", 128));
        int yOffset = cfg.getInt("placement.yOffset", 0);
        boolean requireFlat = cfg.getBoolean("placement.requireFlat", true);
        int maxVarianceCfg = Math.max(0, cfg.getInt("placement.maxVariance", 1));
        double minSolidRatioCfg = Math.min(1.0, Math.max(0.0, cfg.getDouble("placement.minSolidRatio", 0.9)));
        boolean avoidLiquids = cfg.getBoolean("placement.avoidLiquids", true);
        boolean requireLoadedChunks = cfg.getBoolean("placement.requireLoadedChunks", false);
        int maxVariance = relaxFlatness ? Math.max(0, maxVarianceCfg + 2) : maxVarianceCfg;
        double minSolidRatio = relaxFlatness ? Math.max(0.0, minSolidRatioCfg - 0.1) : minSolidRatioCfg;
        Set<Biome> allowedBiomes = loadAllowedBiomes(cfg);
        double minAllowedBiomeRatio = Math.min(1.0, Math.max(0.0, cfg.getDouble("placement.minAllowedBiomeRatio", 0.8)));
        WorldBorder borderForRange = world.getWorldBorder();
        double half = borderForRange.getSize() / 2.0;
        Vector tmpSize = structure.getSize();
        double footprint = Math.max(tmpSize.getX(), tmpSize.getZ());
        int borderLimit = (int) Math.max(0, Math.floor(half - (footprint / 2.0) - 4.0));
        if (borderLimit < max) {
            max = Math.max(min, borderLimit);
        }
        if (min > max) {
            min = max;
        }
        boolean effectiveRequireFlat = relaxFlatness ? false : requireFlat;
        double effectiveMinAllowedBiomeRatio = relaxFlatness ? 0.0 : minAllowedBiomeRatio;
        boolean effectiveAvoidLiquids = relaxFlatness ? false : avoidLiquids;
        Vector size = structure.getSize();
        int tries = (reuseX != null && reuseZ != null) ? 1 : attempts;
        int rejectUnloaded = 0;
        int rejectBiome = 0;
        int rejectFlat = 0;
        int rejectBorder = 0;
        for (int i = 0; i < tries; i++) {
            int x;
            int z;
            if (reuseX != null && reuseZ != null) {
                x = reuseX;
                z = reuseZ;
            } else {
                int r = random.nextInt(max - min + 1) + min;
                double t = random.nextDouble() * Math.PI * 2.0;
                Location c = borderForRange.getCenter();
                x = (int) Math.round(c.getBlockX() + Math.cos(t) * r);
                z = (int) Math.round(c.getBlockZ() + Math.sin(t) * r);
            }
            // Avoid watchdog stalls: never sample unloaded chunks unless we explicitly allow loading
            if (!allowChunkLoad) {
                if (!chunksAvailableForBounds(world, x, z, size)) {
                    rejectUnloaded++;
                    continue; // skip this candidate when chunks are not loaded
                }
            } else {
                if (!chunksAvailableForBounds(world, x, z, size)) {
                    loadChunksForBoundsAsync(world, x, z, size).thenRun(() ->
                        Bukkit.getScheduler().runTask(plugin, () ->
                            generateInternal(false, relaxFlatness, targetWorld, x, z)
                        )
                    );
                    return;
                }
            }
            FlatCheck check = analyzeFootprint(world, x, z, size, effectiveAvoidLiquids, allowedBiomes);
            if (check.allowedBiomeRatio < effectiveMinAllowedBiomeRatio) {
                rejectBiome++;
                continue;
            }
            if (effectiveRequireFlat) {
                if (check.variance > maxVariance || check.solidRatio < minSolidRatio || (effectiveAvoidLiquids && check.hasLiquid)) {
                    rejectFlat++;
                    continue;
                }
            }
            int sy = (int) size.getY();
            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight();
            int yCandidate = (check.maxGround + 1) + yOffset;
            int y = Math.min(Math.max(yCandidate, minY), maxY - sy);
            if (y < minY || y + sy > maxY) {
                rejectBorder++;
                continue;
            }
            if (!fitsInsideBorder(world, x, y, z, size)) {
                rejectBorder++;
                continue;
            }
            Location loc = new Location(world, x, y, z);
            // Optional platform + volume clear to ensure a consistent floor and no grass
            if (cfg.getBoolean("placement.platform.enabled", true)) {
                Material platformBlock = parseMaterial(cfg.getString("placement.platform.block", "STONE_BRICKS"), Material.STONE_BRICKS);
                Material baseFill = parseMaterial(cfg.getString("placement.platform.baseFill", "DIRT"), Material.DIRT);
                int depth = Math.max(1, cfg.getInt("placement.platform.depth", 1));
                terraformPlatform(world, x, z, size, y, platformBlock, baseFill, depth);
            } else {
                preClearSurface(world, x, y, z, size);
            }
            if (cfg.getBoolean("placement.clearVolumeBefore", true)) {
                clearVolume(world, x, y, z, size);
            }
            structure.place(loc, true, StructureRotation.NONE, org.bukkit.block.structure.Mirror.NONE, -1, 1.0f, random);
            if (!requireFlat) {
                fillSupports(world, x, z, size, y);
            }
            cfg.set("generated.enabled", true);
            cfg.set("generated.world", world.getName());
            cfg.set("generated.x", x);
            cfg.set("generated.y", y);
            cfg.set("generated.z", z);
            plugin.saveConfig();
            plugin.getLogger().info("Mace structure placed at " + world.getName() + " (" + x + ", " + y + ", " + z + ")");
            return;
        }
        plugin.getLogger().warning("Failed to place mace structure after " + attempts + " attempts. Check world border and distance range. Rejects: unloaded=" + rejectUnloaded + ", biome=" + rejectBiome + ", flatness/liquid=" + rejectFlat + ", border/height=" + rejectBorder);
    }

    private FlatCheck analyzeFootprint(World world, int originX, int originZ, Vector size, boolean avoidLiquids, Set<Biome> allowed) {
        int sx = (int) size.getX();
        int sz = (int) size.getZ();
        int minGround = Integer.MAX_VALUE;
        int maxGround = Integer.MIN_VALUE;
        int total = 0;
        int solid = 0;
        boolean hasLiquid = false;
        int allowedCount = 0;
        for (int dx = 0; dx < sx; dx++) {
            for (int dz = 0; dz < sz; dz++) {
                int gx = originX + dx;
                int gz = originZ + dz;
                int groundY = world.getHighestBlockYAt(gx, gz);
                if (groundY < minGround) minGround = groundY;
                if (groundY > maxGround) maxGround = groundY;
                // Sample biome at the surface column
                Biome biome = world.getBiome(gx, groundY, gz);
                if (allowed.contains(biome)) {
                    allowedCount++;
                }
                var mat = world.getBlockAt(gx, groundY, gz).getType();
                total++;
                boolean isSolidGround = mat.isSolid() && !Tag.LEAVES.isTagged(mat) && mat != Material.SNOW && mat != Material.CACTUS;
                if (isSolidGround) solid++;
                if (avoidLiquids && (mat == Material.WATER || mat == Material.LAVA)) {
                    hasLiquid = true;
                }
            }
        }
        FlatCheck out = new FlatCheck();
        out.minGround = (minGround == Integer.MAX_VALUE) ? world.getMinHeight() : minGround;
        out.maxGround = (maxGround == Integer.MIN_VALUE) ? world.getMinHeight() : maxGround;
        out.variance = out.maxGround - out.minGround;
        out.solidRatio = total == 0 ? 0 : (solid / (double) total);
        out.hasLiquid = hasLiquid;
        out.allowedBiomeRatio = total == 0 ? 0 : (allowedCount / (double) total);
        return out;
    }

    private void fillSupports(World world, int originX, int originZ, Vector size, int baseY) {
        int sx = (int) size.getX();
        int sz = (int) size.getZ();
        for (int dx = 0; dx < sx; dx++) {
            for (int dz = 0; dz < sz; dz++) {
                int x = originX + dx;
                int z = originZ + dz;
                int groundY = world.getHighestBlockYAt(x, z);
                if (groundY < baseY - 1) {
                    for (int yy = baseY - 1; yy > groundY; yy--) {
                        world.getBlockAt(x, yy, z).setType(Material.STONE, false);
                    }
                }
            }
        }
    }

    private static final class FlatCheck {
        int minGround;
        int maxGround;
        int variance;
        double solidRatio;
        boolean hasLiquid;
        double allowedBiomeRatio;
    }

    private Set<Biome> loadAllowedBiomes(FileConfiguration cfg) {
        List<String> list = cfg.getStringList("placement.allowedBiomes");
        Set<Biome> result = new HashSet<>();
        if (list == null || list.isEmpty()) {
            // Defaults: PLAINS, SAVANNA, TAIGA, MEADOW, BIRCH_FOREST, DARK_FOREST
            result.add(resolveBiomeByKey("minecraft:plains"));
            result.add(resolveBiomeByKey("minecraft:savanna"));
            result.add(resolveBiomeByKey("minecraft:taiga"));
            result.add(resolveBiomeByKey("minecraft:meadow"));
            result.add(resolveBiomeByKey("minecraft:birch_forest"));
            result.add(resolveBiomeByKey("minecraft:dark_forest"));
            return result;
        }
        for (String s : list) {
            Biome b = resolveBiomeByKey(s);
            if (b != null) result.add(b);
        }
        if (result.isEmpty()) {
            Biome fallback = resolveBiomeByKey("minecraft:plains");
            if (fallback != null) result.add(fallback);
        }
        return result;
    }

    private Biome resolveBiomeByKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toLowerCase();
        if (!s.contains(":")) {
            s = "minecraft:" + s.replace(' ', '_');
        }
        NamespacedKey key = NamespacedKey.fromString(s);
        if (key == null) return null;
        try {
            var reg = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
            return reg.get(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Structure loadStructure() {
        FileConfiguration cfg = plugin.getConfig();
        String keyStr = cfg.getString("structure.key");
        if (keyStr != null && !keyStr.isEmpty()) {
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
                        return null;
                    }
                }
            } catch (Exception ignored) {
            }
            return null;
        }
        try {
            return Bukkit.getStructureManager().loadStructure(file);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean fitsInsideBorder(World world, int x, int y, int z, Vector size) {
        WorldBorder border = world.getWorldBorder();
        int sx = (int) size.getX();
        int sy = (int) size.getY();
        int sz = (int) size.getZ();
        if (y < world.getMinHeight() || y + sy > world.getMaxHeight()) {
            return false;
        }
        Location a = new Location(world, x, y, z);
        Location b = new Location(world, x + sx - 1, y, z);
        Location c = new Location(world, x, y, z + sz - 1);
        Location d = new Location(world, x + sx - 1, y, z + sz - 1);
        return border.isInside(a) && border.isInside(b) && border.isInside(c) && border.isInside(d);
    }

    private boolean chunksAvailableForBounds(World world, int x, int z, Vector size) {
        int sx = (int) size.getX();
        int sz = (int) size.getZ();
        int minChunkX = (x) >> 4;
        int minChunkZ = (z) >> 4;
        int maxChunkX = (x + sx - 1) >> 4;
        int maxChunkZ = (z + sz - 1) >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void loadChunksForBounds(World world, int x, int z, Vector size) {
        int sx = (int) size.getX();
        int sz = (int) size.getZ();
        int minChunkX = (x) >> 4;
        int minChunkZ = (z) >> 4;
        int maxChunkX = (x + sx - 1) >> 4;
        int maxChunkZ = (z + sz - 1) >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                world.getChunkAt(cx, cz).load(true);
            }
        }
    }

    private CompletableFuture<Void> loadChunksForBoundsAsync(World world, int x, int z, Vector size) {
        int sx = (int) size.getX();
        int sz = (int) size.getZ();
        int minChunkX = (x) >> 4;
        int minChunkZ = (z) >> 4;
        int maxChunkX = (x + sx - 1) >> 4;
        int maxChunkZ = (z + sz - 1) >> 4;
        CompletableFuture<?>[] futures = new CompletableFuture[(maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1)];
        int idx = 0;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                futures[idx++] = world.getChunkAtAsync(cx, cz);
            }
        }
        return CompletableFuture.allOf(futures).orTimeout(30, TimeUnit.SECONDS).handle((v, ex) -> null);
    }

    private void preClearSurface(World world, int originX, int baseY, int originZ, Vector size) {
        int sx = (int) size.getX();
        int sz = (int) size.getZ();
        for (int dx = 0; dx < sx; dx++) {
            for (int dz = 0; dz < sz; dz++) {
                int x = originX + dx;
                int z = originZ + dz;
                Material below = world.getBlockAt(x, baseY - 1, z).getType();
                if (below == Material.GRASS_BLOCK) {
                    world.getBlockAt(x, baseY - 1, z).setType(Material.DIRT, false);
                }
                for (int yy = baseY; yy < baseY + Math.min(3, world.getMaxHeight() - baseY); yy++) {
                    Material m = world.getBlockAt(x, yy, z).getType();
                    if (!m.isSolid() || Tag.LEAVES.isTagged(m) || m == Material.SHORT_GRASS || m == Material.TALL_GRASS || m == Material.FERN || m == Material.LARGE_FERN || m == Material.SNOW || m == Material.CACTUS || m == Material.DEAD_BUSH) {
                        world.getBlockAt(x, yy, z).setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    private void terraformPlatform(World world, int originX, int originZ, Vector size, int baseY, Material platform, Material baseFill, int depth) {
        int sx = (int) size.getX();
        int sz = (int) size.getZ();
        for (int dx = 0; dx < sx; dx++) {
            for (int dz = 0; dz < sz; dz++) {
                int x = originX + dx;
                int z = originZ + dz;
                // Replace base (y-1 .. y-depth) to a consistent baseFill (e.g., dirt)
                for (int d = 1; d <= depth; d++) {
                    int yy = baseY - d;
                    if (yy >= world.getMinHeight() && yy < world.getMaxHeight()) {
                        world.getBlockAt(x, yy, z).setType(baseFill, false);
                    }
                }
                // Place a uniform platform floor at baseY
                if (baseY >= world.getMinHeight() && baseY < world.getMaxHeight()) {
                    world.getBlockAt(x, baseY, z).setType(platform, false);
                }
            }
        }
    }

    private void clearVolume(World world, int originX, int baseY, int originZ, Vector size) {
        int sx = (int) size.getX();
        int sy = Math.min((int) size.getY() + 2, world.getMaxHeight() - baseY);
        int sz = (int) size.getZ();
        for (int dx = 0; dx < sx; dx++) {
            for (int dz = 0; dz < sz; dz++) {
                int x = originX + dx;
                int z = originZ + dz;
                for (int dy = 0; dy < sy; dy++) {
                    int yy = baseY + dy;
                    world.getBlockAt(x, yy, z).setType(Material.AIR, false);
                }
            }
        }
    }

    private Material parseMaterial(String name, Material def) {
        Material m = Material.matchMaterial(name);
        return m != null ? m : def;
    }
}
