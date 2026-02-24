package net.saturn.maceStructure;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.structure.Structure;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Finds a valid location for the mace structure and places it.
 *
 * Rules:
 *  - Only one generation chain runs at a time (AtomicBoolean guard).
 *  - Chunks are loaded asynchronously when needed; the round then restarts fresh.
 *  - Only surface plants and grass are cleared — no terrain is moved or deleted.
 *  - If no spot meets the criteria after all rounds, the best candidate seen is used.
 */
final class StructurePlacer {

    // Materials considered "passable" — ignored when checking footprint ground and volume.
    // Logs/leaves are passable for volume (trees inside = ok), but NOT for ground detection.
    private static boolean isPassable(Material m) {
        return m == Material.AIR
                || m == Material.CAVE_AIR
                || m == Material.VOID_AIR
                || m == Material.SHORT_GRASS
                || m == Material.TALL_GRASS
                || m == Material.FERN
                || m == Material.LARGE_FERN
                || m == Material.DEAD_BUSH
                || m == Material.VINE
                || m == Material.SNOW
                || m == Material.CACTUS
                || m == Material.SUGAR_CANE
                || m == Material.SEAGRASS
                || m == Material.TALL_SEAGRASS
                || m == Material.KELP
                || m == Material.KELP_PLANT
                || Tag.LEAVES.isTagged(m)
                || Tag.SAPLINGS.isTagged(m)
                || Tag.LOGS.isTagged(m)
                || Tag.FLOWERS.isTagged(m);
    }

    // Surface decoration cleared before placement (subset of passable — excludes logs/leaves)
    private static boolean isSurfaceDecoration(Material m) {
        return m == Material.SHORT_GRASS
                || m == Material.TALL_GRASS
                || m == Material.FERN
                || m == Material.LARGE_FERN
                || m == Material.DEAD_BUSH
                || m == Material.VINE
                || m == Material.SNOW
                || m == Material.CACTUS
                || m == Material.SUGAR_CANE
                || Tag.FLOWERS.isTagged(m)
                || Tag.SAPLINGS.isTagged(m);
    }

    private final MaceStructure plugin;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Random rng = new Random();

    StructurePlacer(MaceStructure plugin) {
        this.plugin = plugin;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    void start(World world) {
        if (alreadyPlaced()) return;
        if (!running.compareAndSet(false, true)) {
            plugin.getLogger().info("Generation already running — ignoring duplicate trigger.");
            return;
        }
        runRound(world, 0, null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Generation round
    // ─────────────────────────────────────────────────────────────────────────

    private void runRound(World world, int round, Candidate bestOverall) {
        if (alreadyPlaced()) { running.set(false); return; }

        FileConfiguration cfg = plugin.getConfig();
        Structure structure = loadStructure(cfg);
        if (structure == null) {
            plugin.getLogger().severe("Cannot load structure file. Aborting.");
            running.set(false);
            return;
        }

        Vector size = structure.getSize();

        // Distance ring
        int minDist = cfg.getInt("placement.minDistance", 2500);
        int maxDist = cfg.getInt("placement.maxDistance", 4000);
        int attempts = cfg.getInt("placement.attempts", 256);
        int maxRounds = cfg.getInt("placement.maxRounds", 5);

        // Clamp max distance to world border
        WorldBorder border = world.getWorldBorder();
        int safeLimit = (int)(border.getSize() / 2.0 - Math.max(size.getX(), size.getZ()) - 8);
        maxDist = Math.min(maxDist, Math.max(minDist, safeLimit));

        // Thresholds — relax each round
        int    maxVariance   = 2  + round * 3;
        double minSolid      = Math.max(0.50, 0.85 - round * 0.10);
        double maxObstruct   = Math.min(0.20, 0.02 + round * 0.05);
        double minBiomeRatio = Math.max(0.0,  0.75 - round * 0.15);

        Set<Biome> allowedBiomes = loadAllowedBiomes(cfg);
        int yOffset = cfg.getInt("placement.yOffset", 0);

        plugin.getLogger().info(String.format(
                "[MaceStructure] Round %d/%d — %d attempts, variance≤%d, solid≥%.0f%%, obstruct≤%.0f%%",
                round + 1, maxRounds + 1, attempts, maxVariance, minSolid * 100, maxObstruct * 100
        ));

        Location center = border.getCenter();
        Candidate bestThisRound = bestOverall;
        int chunkMisses = 0;

        for (int i = 0; i < attempts; i++) {

            // Random point in the ring
            double angle = rng.nextDouble() * 2 * Math.PI;
            int dist = minDist + rng.nextInt(Math.max(1, maxDist - minDist));
            int x = (int) Math.round(center.getBlockX() + Math.cos(angle) * dist);
            int z = (int) Math.round(center.getBlockZ() + Math.sin(angle) * dist);

            // If chunks aren't loaded, load them then restart this round
            if (!chunksLoaded(world, x, z, size)) {
                chunkMisses++;
                loadChunksAsync(world, x, z, size, () -> {});
                continue;
            }

            // Analyse the ground footprint
            Footprint fp = analyseFootprint(world, x, z, size, allowedBiomes);

            // Hard reject: water anywhere in/near footprint
            if (fp.hasWater) continue;

            // Determine placement Y
            int y = Math.max(
                    world.getMinHeight(),
                    Math.min(fp.minGroundY + 1 + yOffset, world.getMaxHeight() - (int) size.getY())
            );

            // Volume obstruction check
            double obstruct = volumeObstruction(world, x, y, z, size);

            // Score: lower is better
            double score = fp.variance * 10
                    + (1.0 - fp.solidRatio) * 40
                    + obstruct * 30
                    + (allowedBiomes.isEmpty() ? 0 : (1.0 - fp.biomeRatio) * 20);

            Candidate candidate = new Candidate(x, y, z, score, fp.variance,
                    fp.solidRatio, obstruct, fp.biomeRatio);

            if (bestThisRound == null || score < bestThisRound.score) {
                bestThisRound = candidate;
            }

            // Check if this round's thresholds are met
            if (fp.variance   <= maxVariance
                    && fp.solidRatio >= minSolid
                    && obstruct      <= maxObstruct
                    && (allowedBiomes.isEmpty() || fp.biomeRatio >= minBiomeRatio)) {

                plugin.getLogger().info(String.format(
                        "[MaceStructure] ✓ Valid spot found at (%d,%d,%d) on attempt %d of round %d",
                        x, y, z, i + 1, round + 1
                ));
                place(world, x, y, z, size, structure);
                return;
            }
        }

        // Round exhausted
        plugin.getLogger().warning(String.format(
                "[MaceStructure] Round %d exhausted %d attempts (chunk misses: %d). Best: %s",
                round + 1, attempts, chunkMisses,
                bestThisRound != null ? bestThisRound : "none"
        ));

        if (round < maxRounds) {
            final Candidate best = bestThisRound;
            Bukkit.getScheduler().runTaskLater(plugin, () -> runRound(world, round + 1, best), 40L);
        } else {
            // All rounds done — place at best candidate found
            if (bestThisRound != null) {
                plugin.getLogger().warning(
                        "[MaceStructure] All rounds exhausted. Placing at best candidate: " + bestThisRound
                );
                place(world, bestThisRound.x, bestThisRound.y, bestThisRound.z, size, structure);
            } else {
                plugin.getLogger().severe(
                        "[MaceStructure] No candidate found at all. " +
                                "Ensure the distance range is within the world border."
                );
                running.set(false);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Placement
    // ─────────────────────────────────────────────────────────────────────────

    private void place(World world, int x, int y, int z, Vector size, Structure structure) {
        clearSurfaceDecoration(world, x, y, z, size);

        structure.place(
                new Location(world, x, y, z),
                true, StructureRotation.NONE, Mirror.NONE, -1, 1.0f, rng
        );

        FileConfiguration cfg = plugin.getConfig();
        cfg.set("generated", true);
        cfg.set("location.world", world.getName());
        cfg.set("location.x", x);
        cfg.set("location.y", y);
        cfg.set("location.z", z);
        plugin.saveConfig();

        plugin.getLogger().info(
                "[MaceStructure] Placed at " + world.getName() + " (" + x + ", " + y + ", " + z + ")"
        );
        running.set(false);
    }

    /** Remove only plants and grass from the structure's footprint. Never touches solid terrain. */
    private void clearSurfaceDecoration(World world, int ox, int baseY, int oz, Vector size) {
        int sx = (int) size.getX(), sy = (int) size.getY(), sz = (int) size.getZ();
        for (int dx = 0; dx < sx; dx++) {
            for (int dz = 0; dz < sz; dz++) {
                for (int dy = 0; dy < sy; dy++) {
                    int by = baseY + dy;
                    if (by < world.getMinHeight() || by >= world.getMaxHeight()) continue;
                    Material m = world.getBlockAt(ox + dx, by, oz + dz).getType();
                    if (isSurfaceDecoration(m)) {
                        world.getBlockAt(ox + dx, by, oz + dz).setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Footprint analysis
    // ─────────────────────────────────────────────────────────────────────────

    private Footprint analyseFootprint(World world, int ox, int oz,
                                       Vector size, Set<Biome> allowed) {
        int sx = (int) size.getX(), sz = (int) size.getZ();
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int solid = 0, total = 0, biomeHits = 0;
        boolean hasWater = false;

        for (int dx = 0; dx < sx; dx++) {
            for (int dz = 0; dz < sz; dz++) {
                int gx = ox + dx, gz = oz + dz;
                int groundY = trueGroundY(world, gx, gz);
                total++;

                if (groundY < minY) minY = groundY;
                if (groundY > maxY) maxY = groundY;

                Material m = world.getBlockAt(gx, groundY, gz).getType();

                if (m == Material.WATER || m == Material.LAVA) {
                    hasWater = true;
                } else if (m.isSolid()) {
                    solid++;
                }

                // Submerged: liquid sits above the ground block
                if (!hasWater && liquidAbove(world, gx, groundY, gz)) hasWater = true;

                // Coast: liquid within 6 blocks horizontally at roughly ground level
                if (!hasWater && liquidNearby(world, gx, groundY, gz, 6)) hasWater = true;

                if (!allowed.isEmpty() && allowed.contains(world.getBiome(gx, groundY, gz))) {
                    biomeHits++;
                }
            }
        }

        Footprint fp = new Footprint();
        fp.minGroundY = minY == Integer.MAX_VALUE ? world.getMinHeight() : minY;
        fp.variance   = maxY == Integer.MIN_VALUE ? 0 : maxY - fp.minGroundY;
        fp.solidRatio = total == 0 ? 0 : solid / (double) total;
        fp.biomeRatio = (allowed.isEmpty() || total == 0) ? 1.0 : biomeHits / (double) total;
        fp.hasWater   = hasWater;
        return fp;
    }

    /** Descend from the highest block to find the first solid non-plant block. */
    private int trueGroundY(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z);
        int min = world.getMinHeight();
        while (y > min) {
            Material m = world.getBlockAt(x, y, z).getType();
            // Stop at anything solid that isn't a plant/leaf
            if (m != Material.AIR && m != Material.CAVE_AIR && m != Material.VOID_AIR
                    && !Tag.LEAVES.isTagged(m) && !Tag.LOGS.isTagged(m)
                    && !Tag.SAPLINGS.isTagged(m) && !Tag.FLOWERS.isTagged(m)
                    && m != Material.SHORT_GRASS && m != Material.TALL_GRASS
                    && m != Material.FERN && m != Material.LARGE_FERN
                    && m != Material.VINE && m != Material.DEAD_BUSH
                    && m != Material.SNOW && m != Material.CACTUS
                    && m != Material.SUGAR_CANE && m != Material.WATER && m != Material.LAVA) {
                return y;
            }
            y--;
        }
        return min;
    }

    private boolean liquidAbove(World world, int x, int groundY, int z) {
        int maxY = world.getMaxHeight();
        for (int y = groundY + 1; y < maxY; y++) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (m == Material.WATER || m == Material.LAVA) return true;
            if (m != Material.AIR && m != Material.CAVE_AIR && m != Material.VOID_AIR) break;
        }
        return false;
    }

    private boolean liquidNearby(World world, int x, int groundY, int z, int radius) {
        int maxY = world.getMaxHeight(), minY = world.getMinHeight();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    int ny = groundY + dy;
                    if (ny < minY || ny >= maxY) continue;
                    Material m = world.getBlockAt(x + dx, ny, z + dz).getType();
                    if (m == Material.WATER || m == Material.LAVA) return true;
                }
            }
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Volume obstruction
    // ─────────────────────────────────────────────────────────────────────────

    private double volumeObstruction(World world, int ox, int baseY, int oz, Vector size) {
        int sx = (int) size.getX(), sy = (int) size.getY(), sz = (int) size.getZ();
        int total = sx * sy * sz, occupied = 0;
        for (int dx = 0; dx < sx; dx++)
            for (int dy = 0; dy < sy; dy++)
                for (int dz = 0; dz < sz; dz++)
                    if (!isPassable(world.getBlockAt(ox + dx, baseY + dy, oz + dz).getType()))
                        occupied++;
        return total == 0 ? 0 : occupied / (double) total;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chunk loading
    // ─────────────────────────────────────────────────────────────────────────

    private boolean chunksLoaded(World world, int x, int z, Vector size) {
        int cxMin = x >> 4, czMin = z >> 4;
        int cxMax = (x + (int) size.getX() - 1) >> 4;
        int czMax = (z + (int) size.getZ() - 1) >> 4;
        for (int cx = cxMin; cx <= cxMax; cx++)
            for (int cz = czMin; cz <= czMax; cz++)
                if (!world.isChunkLoaded(cx, cz)) return false;
        return true;
    }

    private void loadChunksAsync(World world, int x, int z, Vector size, Runnable onDone) {
        int cxMin = x >> 4, czMin = z >> 4;
        int cxMax = (x + (int) size.getX() - 1) >> 4;
        int czMax = (z + (int) size.getZ() - 1) >> 4;
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (int cx = cxMin; cx <= cxMax; cx++)
            for (int cz = czMin; cz <= czMax; cz++)
                futures.add(world.getChunkAtAsync(cx, cz));
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(30, TimeUnit.SECONDS)
                .whenComplete((v, ex) -> {
                    if (ex != null) {
                        plugin.getLogger().warning("[MaceStructure] Chunk load timed out — retrying round.");
                    }
                    // Always continue on the main thread, even after timeout
                    Bukkit.getScheduler().runTask(plugin, onDone);
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Biome loading
    // ─────────────────────────────────────────────────────────────────────────

    private Set<Biome> loadAllowedBiomes(FileConfiguration cfg) {
        Set<Biome> result = new HashSet<>();
        for (String s : cfg.getStringList("placement.allowedBiomes")) {
            String key = s.contains(":") ? s.toLowerCase() : "minecraft:" + s.toLowerCase().replace(' ', '_');
            NamespacedKey nk = NamespacedKey.fromString(key);
            if (nk == null) continue;
            try {
                Biome b = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME).get(nk);
                if (b != null) result.add(b);
                else plugin.getLogger().warning("Unknown biome: " + key);
            } catch (Exception ignored) {}
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Structure loading
    // ─────────────────────────────────────────────────────────────────────────

    private Structure loadStructure(FileConfiguration cfg) {
        String fileName = cfg.getString("structureFile", "mace.nbt");
        File file = new File(plugin.getDataFolder(), fileName);
        if (file.exists()) {
            try { return Bukkit.getStructureManager().loadStructure(file); }
            catch (Exception e) { plugin.getLogger().warning("Failed to load " + fileName + ": " + e.getMessage()); }
        }
        try (InputStream in = plugin.getResource(fileName)) {
            if (in != null) return Bukkit.getStructureManager().loadStructure(in);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load bundled " + fileName + ": " + e.getMessage());
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean alreadyPlaced() {
        return plugin.getConfig().getBoolean("generated", false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner classes
    // ─────────────────────────────────────────────────────────────────────────

    private static final class Footprint {
        int minGroundY, variance;
        double solidRatio, biomeRatio;
        boolean hasWater;
    }

    private static final class Candidate {
        final int x, y, z, variance;
        final double score, solidRatio, obstruction, biomeRatio;

        Candidate(int x, int y, int z, double score, int variance,
                  double solidRatio, double obstruction, double biomeRatio) {
            this.x = x; this.y = y; this.z = z;
            this.score = score; this.variance = variance;
            this.solidRatio = solidRatio; this.obstruction = obstruction;
            this.biomeRatio = biomeRatio;
        }

        @Override
        public String toString() {
            return String.format(
                    "(%d,%d,%d) score=%.1f var=%d solid=%.0f%% obstruct=%.0f%% biome=%.0f%%",
                    x, y, z, score, variance, solidRatio*100, obstruction*100, biomeRatio*100
            );
        }
    }
}
