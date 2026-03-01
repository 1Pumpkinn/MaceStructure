package net.saturn.maceStructure;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.plugin.Plugin;

import java.util.Iterator;

public final class MaceCraftingDisabler implements Listener {

    public MaceCraftingDisabler(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTask(plugin, this::removeMaceRecipes);
    }

    private void removeMaceRecipes() {
        Iterator<Recipe> it = Bukkit.recipeIterator();
        while (it.hasNext()) {
            Recipe r = it.next();
            if (r != null && r.getResult() != null && r.getResult().getType() == Material.MACE) {
                it.remove();
            }
        }
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() != null && event.getRecipe().getResult().getType() == Material.MACE) {
            event.getInventory().setResult(new ItemStack(Material.AIR));
        }
    }

    @EventHandler
    public void onCraft(CraftItemEvent event) {
        if (event.getRecipe() != null && event.getRecipe().getResult().getType() == Material.MACE) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCrafter(CrafterCraftEvent event) {
        ItemStack result = event.getResult();
        if (result != null && result.getType() == Material.MACE) {
            event.setCancelled(true);
            event.setResult(new ItemStack(Material.AIR));
        }
    }
}
