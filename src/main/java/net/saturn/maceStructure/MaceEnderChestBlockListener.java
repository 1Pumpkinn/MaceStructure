package net.saturn.maceStructure;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class MaceEnderChestBlockListener implements Listener {
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top == null || top.getType() != InventoryType.ENDER_CHEST) return;
        if (event.getClickedInventory() == top) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && cursor.getType() == Material.MACE) {
                switch (event.getAction()) {
                    case PLACE_ALL:
                    case PLACE_ONE:
                    case PLACE_SOME:
                    case SWAP_WITH_CURSOR:
                        event.setCancelled(true);
                        return;
                    default:
                        break;
                }
            }
            if (event.getClick() == ClickType.NUMBER_KEY) {
                int hotbar = event.getHotbarButton();
                if (hotbar >= 0) {
                    ItemStack hotbarItem = event.getWhoClicked().getInventory().getItem(hotbar);
                    if (hotbarItem != null && hotbarItem.getType() == Material.MACE) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }
        } else if (event.getClickedInventory() == event.getView().getBottomInventory()) {
            ItemStack current = event.getCurrentItem();
            if (current != null && current.getType() == Material.MACE && event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top == null || top.getType() != InventoryType.ENDER_CHEST) return;
        ItemStack oldCursor = event.getOldCursor();
        if (oldCursor == null || oldCursor.getType() != Material.MACE) return;
        int topSize = top.getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
