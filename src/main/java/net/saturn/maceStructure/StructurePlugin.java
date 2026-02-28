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

        while (attempts < 200) {

            attempts++;

            double angle = random.nextDouble() * 2 * Math.PI;
            int distance = MIN_DISTANCE + random.nextInt(MAX_DISTANCE - MIN_DISTANCE);

            int x = (int) (Math.cos(angle) * distance);
            int z = (int) (Math.sin(angle) * distance);

            world.getChunkAt(x >> 4, z >> 4).load(true);

            int y = world.getHighestBlockYAt(x, z);
            Block ground = world.getBlockAt(x, y - 1, z);

            if (!isValidGround(ground)) continue;

            Location location = new Location(world, x, y, z);

            Structure structure = loadStructureFromPlugin();
            if (structure == null) {
                getLogger().severe("Structure file not found!");
                return;
            }

            structure.place(
                    location,
                    true,
                    StructureRotation.NONE,
                    Mirror.NONE,
                    0,
                    1.0f,
                    random
            );

            // Save coordinates
            getConfig().set("generated", true);
            getConfig().set("structure.x", x);
            getConfig().set("structure.y", y);
            getConfig().set("structure.z", z);
            saveConfig();

            getLogger().info("Structure generated at: " + x + ", " + y + ", " + z);
            return;
        }

        getLogger().warning("Could not find valid location.");
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
        if (biome.name().contains("RIVER")) return false;

        return true;
    }

    // COMMAND SECTION
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
