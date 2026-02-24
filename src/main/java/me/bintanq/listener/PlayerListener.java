package me.bintanq.listener;

import me.bintanq.ContractBoard;
import me.bintanq.model.Contract;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Handles all player-facing event logic:
 * - Player kills (bounty resolution)
 * - Player disconnect (bounty pause)
 * - Player reconnect (bounty resume notification)
 * - Mob kills for XP grind contracts
 * - Prevents soul bottle pickup by non-owners
 */
public class PlayerListener implements Listener {

    private final ContractBoard plugin;

    public PlayerListener(ContractBoard plugin) {
        this.plugin = plugin;
    }

    // ---- Kill Detection ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();

        if (killer == null) return;

        // ---- Bounty: Player killed another player ----
        if (entity instanceof Player victim) {
            plugin.getBountyManager().handleKill(killer, victim);
            return;
        }

        // ---- XP Grind: Mob killed, add XP to active grind session ----
        if (plugin.getXPServiceManager().getActiveGrindSessions().containsKey(killer.getUniqueId())) {
            int xpDropped = event.getDroppedExp();
            if (xpDropped > 0) {
                // Cancel natural drop so only the grind session captures it
                event.setDroppedExp(0);
                plugin.getXPServiceManager().addGrindXP(killer, xpDropped);
            }
        }
    }

    // ---- Bounty Pause on Disconnect ----

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // If this player IS the target of any bounty, the BossBar update loop
        // will naturally detect them as offline and pause the contract.
        // We also stop any tracking the player themselves may have as a hunter.

        // If the player is a hunter tracking someone, leave the task running
        // (it will pause itself). No cleanup needed.
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Notify if there are mail entries
        plugin.getDatabaseManager().getMailForPlayer(player.getUniqueId(), entries -> {
            if (!entries.isEmpty()) {
                player.sendMessage(plugin.getConfigManager().colorize(
                        "&6[ContractBoard] &eYou have &a" + entries.size() +
                                "&e pending mail item(s)! Use &a/contract mail&e to collect."));
            }
        });

        // Resume tracking if they had an active bounty hunt (they rejoined as a hunter)
        plugin.getContractManager().getContractsByWorker(player.getUniqueId()).stream()
                .filter(c -> c.getType() == Contract.ContractType.BOUNTY_HUNT
                        && c.getStatus() == Contract.ContractStatus.PAUSED
                        && plugin.getBountyManager().getBountyForHunter(player.getUniqueId()) == null)
                .findFirst()
                .ifPresent(c -> {
                    // Resume tracking
                    c.setStatus(Contract.ContractStatus.ACCEPTED);
                    plugin.getDatabaseManager().updateContract(c);
                    plugin.getBountyManager().startTracking(player, c);
                    player.sendMessage(plugin.getConfigManager().colorize(
                            "&eBounty tracking resumed for contract #" + c.getId() + "."));
                });
    }

    // ---- Prevent Soul Bottle Theft ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        ItemStack item = event.getItem().getItemStack();
        if (!item.hasItemMeta()) return;

        // Check if it's a soul bottle
        org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(plugin,
                me.bintanq.manager.XPServiceManager.SOUL_BOTTLE_KEY);
        org.bukkit.NamespacedKey cKey = new org.bukkit.NamespacedKey(plugin,
                me.bintanq.manager.XPServiceManager.SOUL_BOTTLE_CONTRACT_KEY);

        var pdc = item.getItemMeta().getPersistentDataContainer();
        if (!pdc.has(key, org.bukkit.persistence.PersistentDataType.BOOLEAN)) return;

        Integer contractId = pdc.get(cKey, org.bukkit.persistence.PersistentDataType.INTEGER);
        if (contractId == null) return;

        // Only the assigned worker can pick it up
        Contract contract = plugin.getContractManager().getContract(contractId).orElse(null);
        if (contract != null && !player.getUniqueId().equals(contract.getWorkerUUID())) {
            event.setCancelled(true);
        }
    }
}
