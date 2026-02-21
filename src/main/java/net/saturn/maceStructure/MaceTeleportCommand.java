package net.saturn.maceStructure;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

final class MaceTeleportCommand implements CommandExecutor {
    private final Plugin plugin;

    MaceTeleportCommand(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("generated.enabled", false)) {
            sender.sendMessage("Mace structure location is not recorded.");
            return true;
        }
        String worldName = cfg.getString("generated.world");
        Integer x = cfg.getInt("generated.x");
        Integer y = cfg.getInt("generated.y");
        Integer z = cfg.getInt("generated.z");
        World world = worldName != null ? Bukkit.getWorld(worldName) : null;
        if (world == null) {
            sender.sendMessage("World is not available.");
            return true;
        }
        Location loc = new Location(world, x + 0.5, y, z + 0.5);
        sender.sendMessage("Mace structure at " + world.getName() + " (" + x + ", " + y + ", " + z + ")");
        if (sender instanceof Player player) {
            player.teleportAsync(loc);
        }
        return true;
    }
}
