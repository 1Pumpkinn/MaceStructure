package net.saturn.maceStructure;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.PluginManager;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.command.brigadier.Commands;
import com.mojang.brigadier.Command;
import java.util.List;

@SuppressWarnings("unused")
public final class MaceStructure extends JavaPlugin {

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Failed to create plugin data folder");
        }

        PluginManager pm = Bukkit.getPluginManager();
        pm.registerEvents(new net.saturn.maceStructure.listener.MaceLootListener(this), this);
        pm.registerEvents(new net.saturn.maceStructure.listener.MaceRecipeBlocker(this), this);

        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final var registrar = event.registrar();

            registrar.register(
                    Commands.literal("macetp")
                            .requires(source -> source.getSender().hasPermission("macestructure.teleport"))
                            .executes(ctx -> {
                                var cfg = getConfig();
                                if (!cfg.getBoolean("generated.enabled", false)) {
                                    ctx.getSource().getSender().sendMessage("Mace structure location is not recorded.");
                                    return Command.SINGLE_SUCCESS;
                                }
                                String worldName = cfg.getString("generated.world");
                                int x = cfg.getInt("generated.x");
                                int y = cfg.getInt("generated.y");
                                int z = cfg.getInt("generated.z");
                                World world = worldName != null ? Bukkit.getWorld(worldName) : null;
                                if (world == null) {
                                    ctx.getSource().getSender().sendMessage("World is not available.");
                                    return Command.SINGLE_SUCCESS;
                                }
                                Location loc = new Location(world, x + 0.5, y, z + 0.5);
                                ctx.getSource().getSender().sendMessage(
                                        "Mace structure at " + world.getName() + " (" + x + ", " + y + ", " + z + ")");
                                if (ctx.getSource().getSender() instanceof Player player) {
                                    player.teleportAsync(loc);
                                }
                                return Command.SINGLE_SUCCESS;
                            })
                            .build(),
                    "Teleport to the mace structure",
                    List.of()
            );

            registrar.register(
                    Commands.literal("macegen")
                            .requires(source -> source.getSender().hasPermission("macestructure.generate"))
                            .executes(ctx -> {
                                MaceGenerator generator = new MaceGenerator(this);
                                var sender = ctx.getSource().getSender();
                                World world = (sender instanceof Player p) ? p.getWorld() : null;
                                generator.generateIfNeeded(true, false, world);
                                ctx.getSource().getSender()
                                        .sendMessage("Generation attempt started. Check console for details.");
                                return Command.SINGLE_SUCCESS;
                            })
                            .then(Commands.literal("force").executes(ctx -> {
                                // Clear generated flag so we can place again
                                var cfg = getConfig();
                                cfg.set("generated.enabled", false);
                                saveConfig();
                                MaceGenerator generator = new MaceGenerator(this);
                                var sender = ctx.getSource().getSender();
                                World world = (sender instanceof Player p) ? p.getWorld() : null;
                                generator.generateIfNeeded(true, true, world);
                                ctx.getSource().getSender()
                                        .sendMessage("Forced generation attempt started. Check console for details.");
                                return Command.SINGLE_SUCCESS;
                            }))
                            .build(),
                    "Generate the mace structure",
                    List.of()
            );
        });

        // Delay startup generation to give the world time to initialise and
        // pre-generate spawn chunks (which are always loaded).  Use
        // allowChunkLoad=true so the generator can async-load distant chunks
        // rather than silently skipping every candidate.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            MaceGenerator generator = new MaceGenerator(this);
            // Use chunk loading on startup so remote chunks are not all rejected
            generator.generateIfNeeded(true, false, null);
        }, 100L); // 5 seconds — enough for worlds to finish loading
    }

    @Override
    public void onDisable() {
    }
}
