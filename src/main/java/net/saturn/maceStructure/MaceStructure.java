package net.saturn.maceStructure;

import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class MaceStructure extends JavaPlugin implements Listener {

    private StructurePlacer placer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();

        placer = new StructurePlacer(this);

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(new MaceListener(this), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            var reg = event.registrar();

            // /macetp — teleport to the structure
            reg.register(
                    Commands.literal("macetp")
                            .requires(src -> src.getSender().hasPermission("macestructure.tp"))
                            .executes(ctx -> {
                                var cfg = getConfig();
                                if (!cfg.getBoolean("generated", false)) {
                                    ctx.getSource().getSender().sendMessage("§cNo structure has been generated yet.");
                                    return Command.SINGLE_SUCCESS;
                                }
                                World world = Bukkit.getWorld(cfg.getString("location.world", "world"));
                                if (world == null) {
                                    ctx.getSource().getSender().sendMessage("§cWorld not found.");
                                    return Command.SINGLE_SUCCESS;
                                }
                                int x = cfg.getInt("location.x");
                                int y = cfg.getInt("location.y");
                                int z = cfg.getInt("location.z");
                                ctx.getSource().getSender().sendMessage(
                                        "§6Structure is at §e" + world.getName() + " (" + x + ", " + y + ", " + z + ")"
                                );
                                if (ctx.getSource().getSender() instanceof Player p) {
                                    p.teleportAsync(new Location(world, x + 0.5, y, z + 0.5));
                                }
                                return Command.SINGLE_SUCCESS;
                            }).build(),
                    "Teleport to the mace structure", List.of()
            );

            // /macegen — manually trigger generation (op only)
            reg.register(
                    Commands.literal("macegen")
                            .requires(src -> src.getSender().hasPermission("macestructure.gen"))
                            .executes(ctx -> {
                                ctx.getSource().getSender().sendMessage("§eStarting structure generation...");
                                World world = ctx.getSource().getSender() instanceof Player p ? p.getWorld() : getTargetWorld();
                                placer.start(world);
                                return Command.SINGLE_SUCCESS;
                            })
                            .then(Commands.literal("force").executes(ctx -> {
                                ctx.getSource().getSender().sendMessage("§eForce-generating structure...");
                                getConfig().set("generated", false);
                                saveConfig();
                                placer = new StructurePlacer(this);
                                World world = ctx.getSource().getSender() instanceof Player p ? p.getWorld() : getTargetWorld();
                                placer.start(world);
                                return Command.SINGLE_SUCCESS;
                            })).build(),
                    "Generate the mace structure", List.of()
            );
        });

        // Generation triggers via WorldLoadEvent below.
        // We use a short delayed task as a fallback in case the world loaded
        // before this plugin and WorldLoadEvent already fired and was missed.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            World w = getTargetWorld();
            if (w != null) placer.start(w);
        }, 20L);
    }

    /** Called when any world finishes loading — catches late-loading worlds. */
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (event.getWorld().getName().equals(getConfig().getString("world", "world"))) {
            placer.start(event.getWorld());
        }
    }

    World getTargetWorld() {
        return Bukkit.getWorld(getConfig().getString("world", "world"));
    }

    @Override
    public void onDisable() {}
}