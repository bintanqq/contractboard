package me.bintanq.listener;

import me.bintanq.ContractBoard;
import me.bintanq.model.Contract;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Handles:
 *  - Player kills → bounty resolution
 *  - Mob kills → XP grind contract progress
 *  - Player join → mail notification + bounty tracking resume
 *  - Player quit → documented (BossBar task self-pauses when target offline)
 *  - Item pickup → prevents non-owner from stealing Soul Bottles
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

        // Player killed another player → check for bounty
        if (entity instanceof Player victim) {
            plugin.getBountyManager().handleKill(killer, victim);
            return;
        }

        // Mob killed → check if killer has an active XP grind session
        if (plugin.getXPServiceManager().getActiveGrindSessions().containsKey(killer.getUniqueId())) {
            int xpDropped = event.getDroppedExp();
            if (xpDropped > 0) {
                event.setDroppedExp(0); // Capture XP into the grind session instead
                plugin.getXPServiceManager().addGrindXP(killer, xpDropped);
            }
        }
    }

    // ---- Player Quit ----

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // If the quitting player is a HUNTER, their BossBar task continues running.
        // The updateBossBar() method checks Bukkit.getPlayer(hunterUUID) and
        // skips the update if the hunter is offline — so no wasted work occurs.
        //
        // If the quitting player is a BOUNTY TARGET, the next BossBar tick for their
        // hunter(s) will detect target==null and auto-pause the contract.
        //
        // Nothing explicit needed here.
    }

    // ---- Player Join ----

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Notify about pending mail
        plugin.getDatabaseManager().getMailForPlayer(player.getUniqueId(), entries -> {
            if (!entries.isEmpty()) {
                player.sendMessage(plugin.getConfigManager().colorize(
                        "&6[ContractBoard] &eYou have &a" + entries.size() +
                                " &epending mail item(s). Use &a/contract mail &eto collect."));
            }
        });

        // Resume bounty tracking if this player was a hunter with a PAUSED bounty
        plugin.getContractManager().getContractsByWorker(player.getUniqueId()).stream()
                .filter(c -> c.getType() == Contract.ContractType.BOUNTY_HUNT
                        && (c.getStatus() == Contract.ContractStatus.PAUSED
                        || c.getStatus() == Contract.ContractStatus.ACCEPTED)
                        && plugin.getBountyManager().getBountyForHunter(player.getUniqueId()) == null)
                .findFirst()
                .ifPresent(c -> {
                    // Re-attach tracking (BossBar loop will auto-resume when target is online)
                    plugin.getBountyManager().startTracking(player, c);
                    player.sendMessage(plugin.getConfigManager().colorize(
                            "&eBounty tracking re-attached for contract &7#&e" + c.getId() + "&e."));
                });
    }

    // ---- Soul Bottle Pickup Protection ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        ItemStack item = event.getItem().getItemStack();
        if (!item.hasItemMeta()) return;

        org.bukkit.NamespacedKey key = new org.bukkit.NamespacedKey(plugin,
                me.bintanq.manager.XPServiceManager.SOUL_BOTTLE_KEY);
        org.bukkit.NamespacedKey cKey = new org.bukkit.NamespacedKey(plugin,
                me.bintanq.manager.XPServiceManager.SOUL_BOTTLE_CONTRACT_KEY);

        var pdc = item.getItemMeta().getPersistentDataContainer();
        if (!pdc.has(key, org.bukkit.persistence.PersistentDataType.BOOLEAN)) return;

        Integer contractId = pdc.get(cKey, org.bukkit.persistence.PersistentDataType.INTEGER);
        if (contractId == null) return;

        Contract contract = plugin.getContractManager().getContract(contractId).orElse(null);
        if (contract != null && !player.getUniqueId().equals(contract.getWorkerUUID())) {
            event.setCancelled(true);
        }
    }
}