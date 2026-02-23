package net.saturn.maceStructure.listener;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

public final class MaceLootListener implements Listener {
    private final JavaPlugin plugin;

    public MaceLootListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onContainerTake(InventoryClickEvent event) {
        HumanEntity who = event.getWhoClicked();
        if (!(who instanceof Player player)) return;
        InventoryView view = event.getView();
        Inventory top = view.getTopInventory();
        Inventory clicked = event.getClickedInventory();
        if (clicked == null || top == null) return;

        // Only care when taking from container side (top inventory)
        if (!clicked.equals(top)) return;
        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType() != Material.MACE) return;

        // Consider any take from top as "obtained from container"
        boolean taking =
                event.getClick() == ClickType.SHIFT_LEFT ||
                event.getClick() == ClickType.SHIFT_RIGHT ||
                event.getAction().name().contains("PICKUP") ||
                event.getAction().name().contains("MOVE_TO_OTHER_INVENTORY");

        if (!taking) return;

        if (plugin.getConfig().getBoolean("announce.broadcasted", false)) return;
        String container = containerName(top, player);
        Bukkit.getServer().broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + player.getName() + " found a Mace from a Structure!" + ChatColor.RESET);
        plugin.getConfig().set("announce.broadcasted", true);
        plugin.saveConfig();
    }

    private String containerName(Inventory top, Player player) {
        try {
            // If this is a block inventory, try to use the block type
            Block target = player.getTargetBlockExact(6);
            if (target != null && target.getState() != null && top.getType() != InventoryType.CRAFTING) {
                return target.getType().name();
            }
        } catch (Throwable ignored) {}
        return top.getType().name();
    }
}
