package net.saturn.maceStructure;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class MaceBarrelBroadcastListener implements Listener {
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top == null || top.getType() != InventoryType.BARREL) return;
        if (event.getAction() != InventoryAction.MOVE_TO_OTHER_INVENTORY) return;
        if (event.getClickedInventory() != top) return;
        ItemStack current = event.getCurrentItem();
        if (current == null || current.getType() != Material.MACE) return;
        Bukkit.broadcastMessage(ChatColor.GOLD + event.getWhoClicked().getName() + ChatColor.YELLOW + " has obtained The Mace.");
    }
}
