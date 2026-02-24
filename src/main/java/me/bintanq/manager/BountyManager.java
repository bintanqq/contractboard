package me.bintanq.manager;

import me.bintanq.ContractBoard;
import me.bintanq.model.Contract;
import me.bintanq.model.Contract.ContractStatus;
import me.bintanq.util.MetadataUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/**
 * Manages Bounty Hunt contracts.
 *
 * NEW FEATURE: Test Mode
 * - Enable with /contract bountytest
 * - Allows killing ANY player to complete bounty (for testing with 2 clients)
 * - Shows special message to indicate test mode is active
 */
public class BountyManager {

    private final ContractBoard plugin;

    // hunterUUID → active bounty contract they are tracking
    private final Map<UUID, Contract> activeBounties = new HashMap<>();

    // hunterUUID → BossBar shown to that hunter
    private final Map<UUID, BossBar> bossBars = new HashMap<>();

    // hunterUUID → repeating BossBar update task
    private final Map<UUID, BukkitTask> trackingTasks = new HashMap<>();

    // NEW: Test mode - allows killing any player to complete bounty
    private final Set<UUID> testModePlayers = new HashSet<>();

    public BountyManager(ContractBoard plugin) {
        this.plugin = plugin;
    }

    // ---- Bounty Posting ----

    public void postBounty(Player contractor, String targetName, double reward, boolean anonymous) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            contractor.sendMessage(stripColor(plugin.getConfigManager().getMessage("contract.not-found")));
            return;
        }
        if (target.getUniqueId().equals(contractor.getUniqueId())) {
            contractor.sendMessage(stripColor(plugin.getConfigManager().getMessage("contract.cannot-self")));
            return;
        }

        // Anonymous fee
        if (anonymous) {
            double extraCost = plugin.getConfigManager().getAnonymousExtraCost();
            if (!plugin.hasEconomy() || !plugin.getEconomy().has(contractor, extraCost)) {
                contractor.sendMessage(stripColor(plugin.getConfigManager().getMessage("contract.insufficient-funds")
                        .replace("{amount}", String.format("%.2f", extraCost))));
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
                            ? plugin.getConfigManager().getMessage("bounty.anonymous-reveal")
                            : stripColor(plugin.getConfigManager().colorize("&e" + contractor.getName()));

                    String notification = stripColor(plugin.getConfigManager().colorize(
                            "&cA bounty of &6" + String.format("%.2f", reward) +
                                    "&c has been placed on your head by " + who + "&c!"));
                    target.sendMessage(notification);
                }
        );
    }

    // ---- Hunter Tracking ----

    public void startTracking(Player hunter, Contract contract) {
        stopTracking(hunter.getUniqueId());

        activeBounties.put(hunter.getUniqueId(), contract);

        BossBar bar = Bukkit.createBossBar(
                plugin.getConfigManager().colorize("&eInitializing tracker..."),
                plugin.getConfigManager().getBossBarColor(),
                plugin.getConfigManager().getBossBarStyle()
        );
        bar.addPlayer(hunter);
        bossBars.put(hunter.getUniqueId(), bar);

        // NEW: Check if test mode is enabled
        if (testModePlayers.contains(hunter.getUniqueId())) {
            hunter.sendMessage(ChatColor.GREEN + "━━━━━━━━━━━━━━━━━━━━━━━━━━");
            hunter.sendMessage(stripColor(plugin.getConfigManager().getMessage("bounty.test-mode-enabled")));
            hunter.sendMessage(stripColor(plugin.getConfigManager().getMessage("bounty.test-mode-info")));
            hunter.sendMessage(ChatColor.GREEN + "━━━━━━━━━━━━━━━━━━━━━━━━━━");
        }

        long intervalTicks = (long) plugin.getConfigManager().getBossBarUpdateInterval() * 20L;

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(
                plugin, () -> updateBossBar(hunter.getUniqueId(), contract), 0L, intervalTicks);
        trackingTasks.put(hunter.getUniqueId(), task);
    }

    private void updateBossBar(UUID hunterUUID, Contract contract) {
        BossBar bar = bossBars.get(hunterUUID);
        if (bar == null) return;

        Player hunter = Bukkit.getPlayer(hunterUUID);
        if (hunter == null) return;

        String targetName = MetadataUtil.getBountyTargetName(contract.getMetadata());
        UUID targetUUID;
        try {
            targetUUID = UUID.fromString(MetadataUtil.getBountyTargetUUID(contract.getMetadata()));
        } catch (IllegalArgumentException e) {
            bar.setTitle(plugin.getConfigManager().colorize("&cInvalid contract data."));
            return;
        }

        Player target = Bukkit.getPlayer(targetUUID);

        // NEW: Test mode indicator
        String testIndicator = testModePlayers.contains(hunterUUID) ? " &a[TEST MODE]" : "";

        if (target == null || !target.isOnline()) {
            if (contract.getStatus() == ContractStatus.ACCEPTED) {
                contract.setStatus(ContractStatus.PAUSED);
                plugin.getDatabaseManager().updateContract(contract);
                hunter.sendMessage(stripColor(plugin.getConfigManager().getMessage("bounty.target-offline")
                        .replace("{target}", targetName)));
            }
            bar.setTitle(plugin.getConfigManager().colorize(
                    "&cTarget: &e" + targetName + " &7| &cOFFLINE — Tracking Paused" + testIndicator));
            return;
        }

        if (contract.getStatus() == ContractStatus.PAUSED) {
            contract.setStatus(ContractStatus.ACCEPTED);
            plugin.getDatabaseManager().updateContract(contract);
            hunter.sendMessage(stripColor(plugin.getConfigManager().colorize(
                    "&aTarget &e" + targetName + " &ais back online. Tracking resumed!")));
        }

        bar.setTitle(plugin.getConfigManager().colorize(
                "&eTarget: &f" + target.getName() +
                        " &7| &eWorld: &f" + target.getWorld().getName() +
                        " &7| &eXYZ: &f" + target.getLocation().getBlockX() +
                        " &7/ &f" + target.getLocation().getBlockY() +
                        " &7/ &f" + target.getLocation().getBlockZ() +
                        testIndicator
        ));
    }

    public void stopTracking(UUID hunterUUID) {
        BukkitTask task = trackingTasks.remove(hunterUUID);
        if (task != null) task.cancel();

        BossBar bar = bossBars.remove(hunterUUID);
        if (bar != null) bar.removeAll();

        activeBounties.remove(hunterUUID);
    }

    // ---- Kill Detection ----

    public void handleKill(Player killer, Player victim) {
        UUID killerUUID = killer.getUniqueId();
        UUID victimUUID = victim.getUniqueId();

        Contract contract = activeBounties.get(killerUUID);
        if (contract == null) return;
        if (contract.getStatus() != ContractStatus.ACCEPTED
                && contract.getStatus() != ContractStatus.PAUSED) return;

        String targetUUIDStr = MetadataUtil.getBountyTargetUUID(contract.getMetadata());
        String targetName = MetadataUtil.getBountyTargetName(contract.getMetadata());

        // NEW: Test mode - accept ANY kill
        boolean isTestMode = testModePlayers.contains(killerUUID);
        boolean isCorrectTarget = victimUUID.toString().equals(targetUUIDStr);

        if (!isCorrectTarget && !isTestMode) {
            return; // Wrong target and not in test mode
        }

        // Complete the bounty
        stopTracking(killerUUID);
        plugin.getContractManager().completeContract(contract, killer);

        // NEW: Different message for test mode
        if (isTestMode && !isCorrectTarget) {
            killer.sendMessage(stripColor(plugin.getConfigManager().getMessage("bounty.test-mode-completed")
                    .replace("{victim}", victim.getName())
                    .replace("{target}", targetName)
                    .replace("{reward}", String.format("%.2f", contract.getReward()))));
        } else {
            killer.sendMessage(stripColor(plugin.getConfigManager().getMessage("bounty.target-killed")
                    .replace("{target}", victim.getName())
                    .replace("{reward}", String.format("%.2f", contract.getReward()))));
        }
    }

    // ---- Test Mode Management ----

    /**
     * Toggles test mode for a player
     * In test mode, ANY player kill will complete the bounty (for testing with 2 clients)
     */
    public void toggleTestMode(Player player) {
        if (testModePlayers.contains(player.getUniqueId())) {
            testModePlayers.remove(player.getUniqueId());
            player.sendMessage(ChatColor.RED + "Bounty test mode disabled.");
        } else {
            testModePlayers.add(player.getUniqueId());
            player.sendMessage(ChatColor.GREEN + "━━━━━━━━━━━━━━━━━━━━━━━━━━");
            player.sendMessage(stripColor(plugin.getConfigManager().getMessage("bounty.test-mode-enabled")));
            player.sendMessage(stripColor(plugin.getConfigManager().getMessage("bounty.test-mode-info")));
            player.sendMessage(ChatColor.GREEN + "━━━━━━━━━━━━━━━━━━━━━━━━━━");
        }
    }

    public boolean isTestMode(UUID playerUUID) {
        return testModePlayers.contains(playerUUID);
    }

    // ---- Cleanup ----

    public void cleanup() {
        trackingTasks.values().forEach(BukkitTask::cancel);
        bossBars.values().forEach(BossBar::removeAll);
        trackingTasks.clear();
        bossBars.clear();
        activeBounties.clear();
        testModePlayers.clear();
    }

    // ---- Getters ----

    public Map<UUID, Contract> getActiveBounties() {
        return Collections.unmodifiableMap(activeBounties);
    }

    public Contract getBountyForHunter(UUID hunterUUID) {
        return activeBounties.get(hunterUUID);
    }

    private String stripColor(String message) {
        return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', message));
    }
}