package net.saturn.maceStructure;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Biome;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.block.BlockState;
import org.bukkit.util.BlockTransformer;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.structure.Structure;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.InputStream;
import java.util.Collections;
import java.util.Random;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class StructurePlugin extends JavaPlugin implements Listener {

    private static final String STRUCTURE_FILE = "structures/mace.nbt";
    private static final int MIN_DISTANCE = 1900;
    private static final int MAX_DISTANCE = 2200;

    private static final int FLAT_CHECK_RADIUS = 3;
    private static final int MAX_HEIGHT_VARIANCE = 3;
    // Structure bounding box — must match your .nbt exactly (from NBT: size [16, 10, 19])
    private static final int STRUCT_WIDTH  = 16; // X
    private static final int STRUCT_HEIGHT = 10; // Y
    private static final int STRUCT_DEPTH  = 19; // Z
    private static final int ATTEMPTS_PER_TICK = 1;
    private static final int MAX_ATTEMPTS = 300;

    private int generationTaskId = -1;
    private int attemptsSoFar = 0;
    private boolean preloadingInProgress = false;

    @Override
    public void onEnable() {
        new MaceCraftingDisabler(this);
        getServer().getPluginManager().registerEvents(new MaceCooldownListener(this), this);
        getServer().getPluginManager().registerEvents(this, this);
        registerCommand("mace", (BasicCommand) (CommandSourceStack source, String[] args) -> {
            if (!(source.getSender() instanceof Player player)) {
                source.getSender().sendMessage("Only players can use this.");
                return;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("cooldown")) {
                try {
                    int seconds = Integer.parseInt(args[1]);
                    if (seconds < 0) {
                        player.sendMessage(ChatColor.RED + "Seconds must be non-negative.");
                        return;
                    }
                    int ticks = seconds * 20;
                    getConfig().set("mace.cooldownTicks", ticks);
                    saveConfig();
                    player.sendMessage(ChatColor.GREEN + "Mace cooldown duration set to " + seconds + "s.");
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Usage: /mace cooldown <seconds>");
                }
            } else {
                player.sendMessage(ChatColor.YELLOW + "Usage: /mace cooldown <seconds>");
            }
        });
        registerCommand("findmace", (BasicCommand) (CommandSourceStack source, String[] args) -> {
            CommandSender sender = source.getSender();
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Only players can use this.");
                return;
            }
            if (!getConfig().getBoolean("generated")) {
                player.sendMessage(ChatColor.RED + "Structure has not generated yet.");
                return;
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
        });

        if (!getConfig().contains("mace.cooldownTicks")) {
            getConfig().set("mace.cooldownTicks", 40); // 2 seconds default
        }
        if (!getConfig().contains("generated")) {
            getConfig().set("generated", false);
            saveConfig();
        }

        if (!getConfig().getBoolean("generated") && !Bukkit.getOnlinePlayers().isEmpty()) {
            startGenerationLoop();
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        if (!getConfig().getBoolean("generated") && generationTaskId == -1) {
            startGenerationLoop();
        }
    }

    private void startGenerationLoop() {
        if (generationTaskId != -1) return;
        attemptsSoFar = 0;
        generationTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (getConfig().getBoolean("generated")) {
                Bukkit.getScheduler().cancelTask(generationTaskId);
                generationTaskId = -1;
                return;
            }
            if (Bukkit.getOnlinePlayers().isEmpty()) return;
            if (preloadingInProgress) return;

            World world = Bukkit.getWorld("world");
            if (world == null) return;

            Random random = new Random();
            double angle = random.nextDouble() * 2 * Math.PI;
            int distance = MIN_DISTANCE + random.nextInt(MAX_DISTANCE - MIN_DISTANCE);
            int x = (int) (Math.cos(angle) * distance);
            int z = (int) (Math.sin(angle) * distance);

            preloadingInProgress = true;
            preloadAreaChunks(world, x, z).whenComplete((v, err) -> Bukkit.getScheduler().runTask(this, () -> {
                preloadingInProgress = false;
                for (int i = 0; i < ATTEMPTS_PER_TICK; i++) {
                    if (tryGenerateOnceWith(world, x, z)) {
                        Bukkit.getScheduler().cancelTask(generationTaskId);
                        generationTaskId = -1;
                        return;
                    }
                    attemptsSoFar++;
                    if (attemptsSoFar >= MAX_ATTEMPTS) {
                        getLogger().warning("Could not find a valid flat location after " + attemptsSoFar + " attempts.");
                        Bukkit.getScheduler().cancelTask(generationTaskId);
                        generationTaskId = -1;
                        return;
                    }
                }
            }));
        }, 100L, 20L);
    }

    private CompletableFuture<Void> preloadAreaChunks(World world, int x, int z) {
        int minX = (x - FLAT_CHECK_RADIUS) >> 4;
        int maxX = (x + FLAT_CHECK_RADIUS + STRUCT_WIDTH) >> 4;
        int minZ = (z - FLAT_CHECK_RADIUS) >> 4;
        int maxZ = (z + FLAT_CHECK_RADIUS + STRUCT_DEPTH) >> 4;
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                futures.add(world.getChunkAtAsync(cx, cz, true));
            }
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private boolean tryGenerateOnceWith(World world, int x, int z) {
        int baseY = world.getHighestBlockYAt(x, z);
        Block ground = world.getBlockAt(x, baseY - 1, z);

        if (!isValidGround(ground)) return false;
        if (!isFlatArea(world, x, baseY, z)) return false;

        Location location = new Location(world, x, baseY, z);
        Structure structure = loadStructureFromPlugin();
        if (structure == null) {
            getLogger().severe("Structure file not found!");
            return false;
        }

        BlockTransformer ignoreAir = (region, bx, by, bz, current, state) -> {
            Material type = current.getType();
            if (type == Material.STRUCTURE_VOID) {
                BlockState worldState = state.getWorld();
                current.setBlockData(worldState.getBlockData()); // keep world outside
                return current;
            }
            if (type.isAir()) {
                current.setType(Material.AIR); // enforce air for interior
                return current;
            }
            return current;
        };

        Random random = new Random();
        structure.place(location, true, StructureRotation.NONE, Mirror.NONE, 0, 1.0f, random, Collections.singleton(ignoreAir), Collections.emptyList());

        getConfig().set("generated", true);
        getConfig().set("structure.x", x);
        getConfig().set("structure.y", baseY);
        getConfig().set("structure.z", z);
        saveConfig();

        getLogger().info("Structure generated at: " + x + ", " + baseY + ", " + z);
        return true;
    }

    /**
     * Checks that ground height within FLAT_CHECK_RADIUS + structure footprint
     * doesn't vary more than MAX_HEIGHT_VARIANCE.
     */
    private boolean isFlatArea(World world, int centerX, int centerY, int centerZ) {
        int minY = centerY;
        int maxY = centerY;

        for (int dx = -FLAT_CHECK_RADIUS; dx <= FLAT_CHECK_RADIUS + STRUCT_WIDTH; dx += 2) {
            for (int dz = -FLAT_CHECK_RADIUS; dz <= FLAT_CHECK_RADIUS + STRUCT_DEPTH; dz += 2) {
                int y = world.getHighestBlockYAt(centerX + dx, centerZ + dz);
                Block b = world.getBlockAt(centerX + dx, y - 1, centerZ + dz);
                if (!isValidGround(b)) return false;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }

        return (maxY - minY) <= MAX_HEIGHT_VARIANCE;
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
