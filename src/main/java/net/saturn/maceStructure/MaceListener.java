package net.saturn.maceStructure;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Handles two responsibilities:
 *  1. Block the mace from being crafted or smithed.
 *  2. Broadcast a one-time message when a player takes the mace from a container.
 */
public final class MaceListener implements Listener {

    private final MaceStructure plugin;

    public MaceListener(MaceStructure plugin) {
        this.plugin = plugin;
        removeMaceRecipes();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Block crafting
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        var result = event.getInventory().getResult();
        if (result != null && result.getType() == Material.MACE) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        var result = event.getInventory().getResult();
        if (result != null && result.getType() == Material.MACE) {
            event.getInventory().setResult(null);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Broadcast when mace is found
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Already announced
        if (plugin.getConfig().getBoolean("announced", false)) return;

        var clicked = event.getClickedInventory();
        var top = event.getView().getTopInventory();
        if (clicked == null || top == null) return;

        // Only trigger when the player takes from the top (container) inventory
        if (!clicked.equals(top)) return;

        var item = event.getCurrentItem();
        if (item == null || item.getType() != Material.MACE) return;

        // Check the action is a pickup
        String action = event.getAction().name();
        if (!action.contains("PICKUP") && !action.contains("MOVE_TO_OTHER_INVENTORY")
                && event.getClick().name().indexOf("SHIFT") == -1) return;

        Bukkit.broadcastMessage(
                plugin.getConfig().getString("announceMessage",
                                "§c§l{player} §r§cfound the Mace from the structure!")
                        .replace("{player}", player.getName())
        );

        plugin.getConfig().set("announced", true);
        plugin.saveConfig();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Remove vanilla mace recipes on startup
    // ─────────────────────────────────────────────────────────────────────────

    private void removeMaceRecipes() {
        List<NamespacedKey> toRemove = new ArrayList<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r = it.next();
            if (r == null) continue;
            var result = r.getResult();
            if (result.getType() == Material.MACE) {
                if (r instanceof Keyed keyed) toRemove.add(keyed.getKey());
                else it.remove();
            }
        }
        for (NamespacedKey key : toRemove) Bukkit.removeRecipe(key);
        if (!toRemove.isEmpty()) {
            plugin.getLogger().info("Removed " + toRemove.size() + " mace recipe(s).");
        }
    }
}