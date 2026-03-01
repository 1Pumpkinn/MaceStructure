package net.saturn.maceStructure;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class MaceCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this.");
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("cooldown")) {
            try {
                int seconds = Integer.parseInt(args[1]);
                if (seconds < 0) {
                    player.sendMessage(ChatColor.RED + "Seconds must be non-negative.");
                    return true;
                }
                int ticks = seconds * 20;
                player.getServer().getPluginManager().getPlugin("MaceStructure")
                        .getConfig().set("mace.cooldownTicks", ticks);
                player.getServer().getPluginManager().getPlugin("MaceStructure").saveConfig();
                player.sendMessage(ChatColor.GREEN + "Mace cooldown duration set to " + seconds + "s.");
                return true;
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Usage: /mace cooldown <seconds>");
                return true;
            }
        }

        player.sendMessage(ChatColor.YELLOW + "Usage: /mace cooldown <seconds>");
        return true;
    }
}
