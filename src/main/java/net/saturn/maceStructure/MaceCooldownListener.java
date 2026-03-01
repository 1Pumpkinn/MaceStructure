package net.saturn.maceStructure;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public final class MaceCooldownListener implements Listener {
    private final Plugin plugin;

    public MaceCooldownListener(Plugin plugin) {
        this.plugin = plugin;
    }

    private int cooldownTicks() {
        return plugin.getConfig().getInt("mace.cooldownTicks", 40);
    }

    @EventHandler
    public void onRightClick(PlayerInteractEvent event) {
        // Apply cooldown for right-click uses with a mace (mirrors shield/wind charge UX)
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.MACE) return;
        Player p = event.getPlayer();
        if (!p.hasCooldown(Material.MACE)) {
            p.setCooldown(Material.MACE, cooldownTicks());
        } else {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onMelee(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player p)) return;
        ItemStack main = p.getInventory().getItemInMainHand();
        if (main == null || main.getType() != Material.MACE) return;
        if (p.hasCooldown(Material.MACE)) {
            event.setCancelled(true);
            return;
        }
        p.setCooldown(Material.MACE, cooldownTicks());
    }
}
