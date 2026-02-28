package net.saturn.maceStructure;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Biome;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.structure.Structure;

import java.io.InputStream;
import java.util.Random;

public final class StructurePlugin extends JavaPlugin {

    private static final String STRUCTURE_FILE = "structures/mace.nbt";
    private static final int MIN_DISTANCE = 1900;
    private static final int MAX_DISTANCE = 2200;

    // How flat the ground must be around the structure origin (in blocks)
    private static final int FLAT_CHECK_RADIUS = 6;
    // Max allowed height variation across the checked area
    private static final int MAX_HEIGHT_VARIANCE = 2;
    // Structure bounding box (adjust to match your .nbt size)
    private static final int STRUCT_WIDTH  = 13; // X
    private static final int STRUCT_HEIGHT = 12; // Y
    private static final int STRUCT_DEPTH  = 13; // Z
    // How many blocks to sink the structure into the ground
    private static final int SINK_DEPTH = 1;

    @Override
    public void onEnable() {
        if (!getConfig().contains("generated")) {
            getConfig().set("generated", false);
            saveConfig();
        }

        if (!getConfig().getBoolean("generated")) {
            Bukkit.getScheduler().runTask(this, this::generateStructureOnce);
        }
    }

    private void generateStructureOnce() {
        World world = Bukkit.getWorld("world");
        if (world == null) {
            getLogger().severe("World not found!");
            return;
        }

        Random random = new Random();
        int attempts = 0;

        while (attempts < 300) {
            attempts++;

            double angle = random.nextDouble() * 2 * Math.PI;
            int distance = MIN_DISTANCE + random.nextInt(MAX_DISTANCE - MIN_DISTANCE);

            int x = (int) (Math.cos(angle) * distance);
            int z = (int) (Math.sin(angle) * distance);

            // Load all chunks in the area we'll be checking
            for (int cx = (x - FLAT_CHECK_RADIUS) >> 4; cx <= (x + FLAT_CHECK_RADIUS + STRUCT_WIDTH) >> 4; cx++) {
                for (int cz = (z - FLAT_CHECK_RADIUS) >> 4; cz <= (z + FLAT_CHECK_RADIUS + STRUCT_DEPTH) >> 4; cz++) {
                    world.getChunkAt(cx, cz).load(true);
                }
            }

            int baseY = world.getHighestBlockYAt(x, z);
            Block ground = world.getBlockAt(x, baseY - 1, z);

            if (!isValidGround(ground)) continue;
            if (!isFlatArea(world, x, baseY, z)) continue;

            // Grab the natural ground material (sand, grass, dirt, etc.)
            Material groundMaterial = ground.getType();

            // Sink placement by SINK_DEPTH so structure sits slightly into the ground
            int placeY = baseY - SINK_DEPTH;

            // Step 1: Only clear blocks STRICTLY within the structure's footprint, from placeY upward.
            // Do NOT touch anything outside the footprint.
            clearStructureFootprint(world, x, placeY, z);

            // Step 2: Fill the sunk layer under the footprint solid so there's no void/floating base.
            // This replaces any air that was exposed by sinking.
            fillSunkLayer(world, x, placeY, z, groundMaterial);

            // Step 3: Place the structure
            Location location = new Location(world, x, placeY, z);
            Structure structure = loadStructureFromPlugin();
            if (structure == null) {
                getLogger().severe("Structure file not found!");
                return;
            }

            structure.place(location, true, StructureRotation.NONE, Mirror.NONE, 0, 1.0f, random);

            getConfig().set("generated", true);
            getConfig().set("structure.x", x);
            getConfig().set("structure.y", placeY);
            getConfig().set("structure.z", z);
            saveConfig();

            getLogger().info("Structure generated at: " + x + ", " + placeY + ", " + z);
            return;
        }

        getLogger().warning("Could not find a valid flat location after 300 attempts.");
    }

    /**
     * Checks that ground height within FLAT_CHECK_RADIUS doesn't vary more than MAX_HEIGHT_VARIANCE.
     */
    private boolean isFlatArea(World world, int centerX, int centerY, int centerZ) {
        int minY = centerY;
        int maxY = centerY;

        for (int dx = -FLAT_CHECK_RADIUS; dx <= FLAT_CHECK_RADIUS + STRUCT_WIDTH; dx++) {
            for (int dz = -FLAT_CHECK_RADIUS; dz <= FLAT_CHECK_RADIUS + STRUCT_DEPTH; dz++) {
                int y = world.getHighestBlockYAt(centerX + dx, centerZ + dz);
                Block b = world.getBlockAt(centerX + dx, y - 1, centerZ + dz);
                if (!isValidGround(b)) return false;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }

        return (maxY - minY) <= MAX_HEIGHT_VARIANCE;
    }

    /**
     * Clears ONLY the exact structure footprint volume (x to x+WIDTH, z to z+DEPTH)
     * starting from placeY upward. Does NOT touch any blocks outside this rectangle.
     * This prevents the "moat" effect around the structure.
     */
    private void clearStructureFootprint(World world, int x, int y, int z) {
        for (int dy = 0; dy < STRUCT_HEIGHT + SINK_DEPTH; dy++) {
            for (int dx = 0; dx < STRUCT_WIDTH; dx++) {
                for (int dz = 0; dz < STRUCT_DEPTH; dz++) {
                    Block b = world.getBlockAt(x + dx, y + dy, z + dz);
                    if (b.getType() != Material.AIR && b.getType() != Material.CAVE_AIR) {
                        b.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    /**
     * Fills the sunk layer (the SINK_DEPTH rows below baseY) within the structure footprint
     * with the ground material. This ensures the structure base doesn't float —
     * the bottom row of the structure will sit on solid ground rather than air.
     *
     * Only fills blocks that are currently air (won't overwrite existing solid terrain).
     */
    private void fillSunkLayer(World world, int x, int placeY, int z, Material groundMaterial) {
        // Fill from placeY (inclusive) for SINK_DEPTH layers — these are the buried rows
        for (int dy = 0; dy < SINK_DEPTH; dy++) {
            for (int dx = 0; dx < STRUCT_WIDTH; dx++) {
                for (int dz = 0; dz < STRUCT_DEPTH; dz++) {
                    Block b = world.getBlockAt(x + dx, placeY + dy, z + dz);
                    if (b.getType() == Material.AIR || b.getType() == Material.CAVE_AIR) {
                        b.setType(groundMaterial, false);
                    }
                }
            }
        }
    }

    private Structure loadStructureFromPlugin() {
        try (InputStream inputStream = getResource(STRUCTURE_FILE)) {
            if (inputStream == null) return null;
            return Bukkit.getStructureManager().loadStructure(inputStream);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private boolean isValidGround(Block block) {
        Material type = block.getType();
        if (!type.isSolid()) return false;
        if (type == Material.WATER) return false;
        if (type.name().contains("LEAVES")) return false;
        if (type.name().contains("LOG")) return false;

        Biome biome = block.getBiome();
        if (biome.name().contains("OCEAN")) return false;
        if (biome.name().contains("DEEP_COLD_OCEAN")) return false;
        if (biome.name().contains("DEEP_DARK")) return false;
        if (biome.name().contains("DEEP_FROZEN_OCEAN")) return false;
        if (biome.name().contains("DEEP_LUKEWARM_OCEAN")) return false;
        if (biome.name().contains("ICE_SPIKES")) return false;
        if (biome.name().contains("COLD_OCEAN")) return false;
        if (biome.name().contains("JAGGED_PEAKS")) return false;
        if (biome.name().contains("FROZEN_RIVER")) return false;
        if (biome.name().contains("FROZEN_PEAKS")) return false;
        if (biome.name().contains("LUKEWARM_OCEAN")) return false;
        if (biome.name().contains("FROZEN_OCEAN")) return false;
        if (biome.name().contains("WARM_OCEAN")) return false;
        if (biome.name().contains("WOODED_BADLANDS")) return false;
        if (biome.name().contains("DEEP_OCEAN")) return false;
        if (biome.name().contains("SWAMP")) return false;
        if (biome.name().contains("RIVER")) return false;
        if (biome.name().contains("BEACH")) return false;
        if (biome.name().contains("DESERT")) return false;
        if (biome.name().contains("BADLANDS")) return false;
        if (biome.name().contains("ERODED_BADLANDS")) return false;


        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("findmace")) return false;

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this.");
            return true;
        }

        if (!getConfig().getBoolean("generated")) {
            player.sendMessage(ChatColor.RED + "Structure has not generated yet.");
            return true;
        }

        int x = getConfig().getInt("structure.x");
        int y = getConfig().getInt("structure.y");
        int z = getConfig().getInt("structure.z");

        Location structureLoc = new Location(player.getWorld(), x, y, z);
        double distance = player.getLocation().distance(structureLoc);

        player.sendMessage(ChatColor.GOLD + "Mace Structure Location:");
        player.sendMessage(ChatColor.YELLOW + "X: " + x + " Y: " + y + " Z: " + z);
        player.sendMessage(ChatColor.GREEN + "Distance: " + (int) distance + " blocks");

        player.setCompassTarget(structureLoc);
        player.sendMessage(ChatColor.AQUA + "Your compass now points to the structure.");

        return true;
    }
}