package me.bintanq.manager;

import me.bintanq.ContractBoard;
import me.bintanq.model.Contract;
import me.bintanq.model.Contract.ContractStatus;
import me.bintanq.util.MetadataUtil;
import org.bukkit.Bukkit;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Manages Bounty Hunt contracts.
 *
 * Fix applied:
 *   The original handleKill() used Optional<Map.Entry<...>> whose type inference
 *   broke when assigning entry.getKey() / entry.getValue() because the compiler
 *   couldn't resolve the wildcard on the nested generic. Fixed by calling
 *   .orElse(null) and explicitly typing the local variables as UUID and Contract.
 *
 * Performance notes:
 *   - BossBar update tasks run on the MAIN thread (required for Bukkit API) but
 *     are lightweight: one UUID map lookup + one player location read per hunter.
 *   - We store one task per hunter rather than a single global scanner to avoid
 *     iterating all hunters every tick.
 */
public class BountyManager {

    private final ContractBoard plugin;

    // hunterUUID → active bounty contract they are tracking
    private final Map<UUID, Contract> activeBounties = new HashMap<>();

    // hunterUUID → BossBar shown to that hunter
    private final Map<UUID, BossBar> bossBars = new HashMap<>();

    // hunterUUID → repeating BossBar update task
    private final Map<UUID, BukkitTask> trackingTasks = new HashMap<>();

    public BountyManager(ContractBoard plugin) {
        this.plugin = plugin;
    }

    // ---- Bounty Posting ----

    /**
     * Posts a new bounty contract.
     * Metadata: "targetUUID|targetName|anonymous"
     *
     * Anonymous extra cost is an additional flat fee withdrawn from the contractor
     * BEFORE the normal reward+tax is processed by ContractManager.
     */
    public void postBounty(Player contractor, String targetName, double reward, boolean anonymous) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            contractor.sendMessage(plugin.getConfigManager().getMessage("contract.not-found"));
            return;
        }
        if (target.getUniqueId().equals(contractor.getUniqueId())) {
            contractor.sendMessage(plugin.getConfigManager().getMessage("contract.cannot-self"));
            return;
        }

        // Anonymous fee: extra cost on top of the standard reward+tax
        if (anonymous) {
            double extraCost = plugin.getConfigManager().getAnonymousExtraCost();
            if (!plugin.hasEconomy() || !plugin.getEconomy().has(contractor, extraCost)) {
                contractor.sendMessage(plugin.getConfigManager().getMessage("contract.insufficient-funds")
                        .replace("{amount}", String.format("%.2f", extraCost)));
                return;
            }
            plugin.getEconomy().withdrawPlayer(contractor, extraCost);
        }

        String metadata = MetadataUtil.buildBountyMeta(
                target.getUniqueId().toString(), target.getName(), anonymous);

        plugin.getContractManager().createContract(
                contractor, Contract.ContractType.BOUNTY_HUNT, reward, metadata,
                contract -> {
                    // Notify target
                    String who = anonymous
                            ? plugin.getConfigManager().colorize("&ean anonymous contractor")
                            : plugin.getConfigManager().colorize("&e" + contractor.getName());
                    target.sendMessage(plugin.getConfigManager().colorize(
                            "&cA bounty of &6" + String.format("%.2f", reward) +
                                    "&c has been placed on your head by " + who + "&c!"));
                }
        );
    }

    // ---- Hunter Tracking ----

    /**
     * Starts the BossBar tracking loop for a hunter.
     * Called when a hunter accepts a bounty contract.
     */
    public void startTracking(Player hunter, Contract contract) {
        // Remove any stale tracking for this hunter first
        stopTracking(hunter.getUniqueId());

        activeBounties.put(hunter.getUniqueId(), contract);

        BossBar bar = Bukkit.createBossBar(
                plugin.getConfigManager().colorize("&eInitializing tracker..."),
                plugin.getConfigManager().getBossBarColor(),
                plugin.getConfigManager().getBossBarStyle()
        );
        bar.addPlayer(hunter);
        bossBars.put(hunter.getUniqueId(), bar);

        long intervalTicks = (long) plugin.getConfigManager().getBossBarUpdateInterval() * 20L;

        // Run on main thread — BossBar API and Player.getLocation() are not thread-safe
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(
                plugin, () -> updateBossBar(hunter.getUniqueId(), contract), 0L, intervalTicks);
        trackingTasks.put(hunter.getUniqueId(), task);
    }

    private void updateBossBar(UUID hunterUUID, Contract contract) {
        BossBar bar = bossBars.get(hunterUUID);
        if (bar == null) return;

        Player hunter = Bukkit.getPlayer(hunterUUID);
        if (hunter == null) return; // Hunter offline; task will be cleaned up on quit/join logic

        String targetName = MetadataUtil.getBountyTargetName(contract.getMetadata());
        UUID targetUUID;
        try {
            targetUUID = UUID.fromString(MetadataUtil.getBountyTargetUUID(contract.getMetadata()));
        } catch (IllegalArgumentException e) {
            bar.setTitle(plugin.getConfigManager().colorize("&cInvalid contract data."));
            return;
        }

        Player target = Bukkit.getPlayer(targetUUID);

        if (target == null || !target.isOnline()) {
            // Target offline → pause
            if (contract.getStatus() == ContractStatus.ACCEPTED) {
                contract.setStatus(ContractStatus.PAUSED);
                plugin.getDatabaseManager().updateContract(contract);
                hunter.sendMessage(plugin.getConfigManager().getMessage("bounty.target-offline")
                        .replace("{target}", targetName));
            }
            bar.setTitle(plugin.getConfigManager().colorize(
                    "&cTarget: &e" + targetName + " &7| &cOFFLINE — Tracking Paused"));
            return;
        }

        // Target online → resume if was paused
        if (contract.getStatus() == ContractStatus.PAUSED) {
            contract.setStatus(ContractStatus.ACCEPTED);
            plugin.getDatabaseManager().updateContract(contract);
            hunter.sendMessage(plugin.getConfigManager().colorize(
                    "&aTarget &e" + targetName + " &ais back online. Tracking resumed!"));
        }

        bar.setTitle(plugin.getConfigManager().colorize(
                "&eTarget: &f" + target.getName() +
                        " &7| &eWorld: &f" + target.getWorld().getName() +
                        " &7| &eXYZ: &f" + target.getLocation().getBlockX() +
                        " &7/ &f" + target.getLocation().getBlockY() +
                        " &7/ &f" + target.getLocation().getBlockZ()
        ));
    }

    /**
     * Stops and cleans up all tracking resources for a hunter.
     */
    public void stopTracking(UUID hunterUUID) {
        BukkitTask task = trackingTasks.remove(hunterUUID);
        if (task != null) task.cancel();

        BossBar bar = bossBars.remove(hunterUUID);
        if (bar != null) bar.removeAll();

        activeBounties.remove(hunterUUID);
    }

    // ---- Kill Detection ----

    /**
     * Called from PlayerListener on EntityDeathEvent when a player is killed.
     *
     * Fix: the original code declared Optional<Map.Entry<UUID, Contract>> entry
     * and then tried to call entry.getKey() / entry.getValue() — this fails
     * because Optional does not have getKey()/getValue(). You must call
     * entry.get() first to obtain the Map.Entry, or use ifPresent().
     * We use a null-check pattern here which is clearer and avoids the issue.
     */
    public void handleKill(Player killer, Player victim) {
        UUID killerUUID = killer.getUniqueId();
        UUID victimUUID = victim.getUniqueId();

        // Check if this killer is actively tracking a bounty on this victim
        Contract contract = activeBounties.get(killerUUID);
        if (contract == null) return;
        if (contract.getStatus() != ContractStatus.ACCEPTED
                && contract.getStatus() != ContractStatus.PAUSED) return;

        // Verify this contract's target is indeed the victim
        String targetUUIDStr = MetadataUtil.getBountyTargetUUID(contract.getMetadata());
        if (!victimUUID.toString().equals(targetUUIDStr)) return;

        // Complete the bounty
        stopTracking(killerUUID);
        plugin.getContractManager().completeContract(contract, killer);

        killer.sendMessage(plugin.getConfigManager().getMessage("bounty.target-killed")
                .replace("{target}", victim.getName())
                .replace("{reward}", String.format("%.2f", contract.getReward())));
    }

    // ---- Cleanup ----

    /** Called on plugin disable. */
    public void cleanup() {
        trackingTasks.values().forEach(BukkitTask::cancel);
        bossBars.values().forEach(BossBar::removeAll);
        trackingTasks.clear();
        bossBars.clear();
        activeBounties.clear();
    }

    // ---- Getters ----

    public Map<UUID, Contract> getActiveBounties() {
        return Collections.unmodifiableMap(activeBounties);
    }

    public Contract getBountyForHunter(UUID hunterUUID) {
        return activeBounties.get(hunterUUID);
    }
}