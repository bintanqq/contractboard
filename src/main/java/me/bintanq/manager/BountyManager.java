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
import java.util.Map;

/**
 * Manages Bounty Hunt contracts specifically.
 * Handles BossBar tracking, target-offline pause logic, and kill detection.
 */
public class BountyManager {

    private final ContractBoard plugin;

    // hunterUUID -> active bounty contract
    private final Map<UUID, Contract> activeBounties = new HashMap<>();

    // hunterUUID -> BossBar instance
    private final Map<UUID, BossBar> bossBars = new HashMap<>();

    // hunterUUID -> repeating task ID
    private final Map<UUID, BukkitTask> trackingTasks = new HashMap<>();

    public BountyManager(ContractBoard plugin) {
        this.plugin = plugin;
    }

    // ---- Bounty Creation ----

    /**
     * Posts a new bounty contract. Called from GUI after validation.
     * Metadata format: "target_uuid|target_name|anonymous"
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

        // Extra cost for anonymity
        double totalReward = reward;
        if (anonymous) {
            double extraCost = plugin.getConfigManager().getAnonymousExtraCost();
            if (!plugin.getEconomy().has(contractor, extraCost)) {
                contractor.sendMessage(plugin.getConfigManager().getMessage("contract.insufficient-funds")
                        .replace("{amount}", String.format("%.2f", extraCost)));
                return;
            }
            plugin.getEconomy().withdrawPlayer(contractor, extraCost);
        }

        String metadata = MetadataUtil.buildBountyMeta(
                target.getUniqueId().toString(), target.getName(), anonymous);

        plugin.getContractManager().createContract(contractor, Contract.ContractType.BOUNTY_HUNT,
                totalReward, metadata, contract -> {
                    // Notify target (if not anonymous)
                    if (!anonymous) {
                        target.sendMessage(plugin.getConfigManager().colorize(
                                "&cA bounty has been placed on your head by &e" + contractor.getName() + "&c!"));
                    } else {
                        target.sendMessage(plugin.getConfigManager().colorize(
                                "&cA bounty has been placed on your head by &ean anonymous contractor&c!"));
                    }
                });
    }

    // ---- Hunter Tracking ----

    /**
     * Starts BossBar tracking for a hunter who accepted a bounty.
     */
    public void startTracking(Player hunter, Contract contract) {
        activeBounties.put(hunter.getUniqueId(), contract);

        BossBar bar = Bukkit.createBossBar(
                "Initializing...",
                plugin.getConfigManager().getBossBarColor(),
                plugin.getConfigManager().getBossBarStyle()
        );
        bar.addPlayer(hunter);
        bossBars.put(hunter.getUniqueId(), bar);

        int intervalTicks = plugin.getConfigManager().getBossBarUpdateInterval() * 20;
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> updateBossBar(hunter, contract), 0L, intervalTicks);
        trackingTasks.put(hunter.getUniqueId(), task);
    }

    private void updateBossBar(Player hunter, Contract contract) {
        BossBar bar = bossBars.get(hunter.getUniqueId());
        if (bar == null) return;

        String targetName = MetadataUtil.getBountyTargetName(contract.getMetadata());
        Player target = Bukkit.getPlayer(UUID.fromString(MetadataUtil.getBountyTargetUUID(contract.getMetadata())));

        if (target == null || !target.isOnline()) {
            // Target offline: pause contract
            if (contract.getStatus() == ContractStatus.ACCEPTED) {
                contract.setStatus(ContractStatus.PAUSED);
                plugin.getDatabaseManager().updateContract(contract);
                hunter.sendMessage(plugin.getConfigManager().getMessage("bounty.target-offline")
                        .replace("{target}", targetName));
            }
            bar.setTitle(plugin.getConfigManager().colorize(
                    "&cTarget: &e" + targetName + " &7| &cOFFLINE - Tracking Paused"));
            return;
        }

        // Resume if it was paused
        if (contract.getStatus() == ContractStatus.PAUSED) {
            contract.setStatus(ContractStatus.ACCEPTED);
            plugin.getDatabaseManager().updateContract(contract);
        }

        String title = plugin.getConfigManager().colorize(
                "&eTarget: &f" + target.getName() +
                        " &7| &eWorld: &f" + target.getWorld().getName() +
                        " &7| &eCoords: &f" + target.getLocation().getBlockX() +
                        ", " + target.getLocation().getBlockY() +
                        ", " + target.getLocation().getBlockZ()
        );
        bar.setTitle(title);
    }

    /**
     * Stops all tracking for a specific hunter.
     */
    public void stopTracking(UUID hunterUUID) {
        BossBar bar = bossBars.remove(hunterUUID);
        if (bar != null) bar.removeAll();

        BukkitTask task = trackingTasks.remove(hunterUUID);
        if (task != null) task.cancel();

        activeBounties.remove(hunterUUID);
    }

    // ---- Kill Handler ----

    /**
     * Called from PlayerListener when a player dies.
     * Checks if victim has an active bounty and awards the hunter.
     */
    public void handleKill(Player killer, Player victim) {
        // Find a bounty contract targeting this victim
        Optional<Map.Entry<UUID, Contract>> entry = activeBounties.entrySet().stream()
                .filter(e -> {
                    String targetUUID = MetadataUtil.getBountyTargetUUID(e.getValue().getMetadata());
                    return victim.getUniqueId().toString().equals(targetUUID)
                            && e.getValue().getStatus() == ContractStatus.ACCEPTED;
                })
                .filter(e -> e.getKey().equals(killer.getUniqueId()))
                .findFirst();

        if (entry.isEmpty()) return;

        UUID hunterUUID = entry.getKey();
        Contract contract = entry.getValue();

        // Stop tracking
        stopTracking(hunterUUID);

        // Complete contract
        plugin.getContractManager().completeContract(contract, killer);

        killer.sendMessage(plugin.getConfigManager().getMessage("bounty.target-killed")
                .replace("{target}", victim.getName())
                .replace("{reward}", String.format("%.2f", contract.getReward())));
    }

    /**
     * Called on plugin disable to clean up all boss bars.
     */
    public void cleanup() {
        trackingTasks.values().forEach(BukkitTask::cancel);
        bossBars.values().forEach(BossBar::removeAll);
        trackingTasks.clear();
        bossBars.clear();
        activeBounties.clear();
    }

    public Map<UUID, Contract> getActiveBounties() { return Collections.unmodifiableMap(activeBounties); }

    /**
     * Returns the active bounty contract a hunter is tracking, or null.
     */
    public Contract getBountyForHunter(UUID hunterUUID) {
        return activeBounties.get(hunterUUID);
    }
}
