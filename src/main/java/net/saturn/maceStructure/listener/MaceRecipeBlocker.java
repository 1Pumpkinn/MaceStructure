package net.saturn.maceStructure.listener;

import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class MaceRecipeBlocker implements Listener {
    private final Plugin plugin;

    public MaceRecipeBlocker(Plugin plugin) {
        this.plugin = plugin;
        removeMaceRecipes();
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result != null && result.getType() == Material.MACE) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler
    public void onPrepareSmithing(PrepareSmithingEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result != null && result.getType() == Material.MACE) {
            event.getInventory().setResult(null);
        }
    }

    private void removeMaceRecipes() {
        List<NamespacedKey> toRemove = new ArrayList<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r = it.next();
            if (r != null && r.getResult() != null && r.getResult().getType() == Material.MACE) {
                if (r instanceof Keyed keyed) {
                    toRemove.add(keyed.getKey());
                } else {
                    // Fallback: remove via iterator if keyless
                    it.remove();
                }
            }
        }
        for (NamespacedKey key : toRemove) {
            Bukkit.removeRecipe(key);
        }
        if (!toRemove.isEmpty()) {
            plugin.getLogger().info("Removed " + toRemove.size() + " mace recipes.");
        }
    }
}
