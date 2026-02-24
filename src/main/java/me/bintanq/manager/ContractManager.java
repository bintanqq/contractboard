package me.bintanq.manager;

import me.bintanq.ContractBoard;
import me.bintanq.model.Contract;
import me.bintanq.model.Contract.ContractStatus;
import me.bintanq.model.Contract.ContractType;
import me.bintanq.util.MetadataUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Central manager for all contract lifecycle operations.
 *
 * ADDED: Announcement system for new contracts
 */
public class ContractManager {

    private final ContractBoard plugin;

    // In-memory cache of active contracts (thread-safe map, main-thread writes via callbacks)
    private final Map<Integer, Contract> activeContracts = new ConcurrentHashMap<>();

    private BukkitTask expirationTask;

    public ContractManager(ContractBoard plugin) {
        this.plugin = plugin;
        loadContracts();
    }

    // ---- Startup ----

    private void loadContracts() {
        plugin.getDatabaseManager().loadActiveContracts(contracts -> {
            for (Contract c : contracts) {
                activeContracts.put(c.getId(), c);
            }
            plugin.getLogger().info("Loaded " + contracts.size() + " active contracts into cache.");
        });
    }

    // ---- Expiration Task ----

    public void startExpirationTask() {
        expirationTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::processExpiredContracts, 1200L, 600L);
    }

    private void processExpiredContracts() {
        long now = System.currentTimeMillis();

        List<Contract> toExpire = activeContracts.values().stream()
                .filter(c -> c.isActive() && c.getExpiresAt() < now)
                .toList();

        if (toExpire.isEmpty()) return;

        for (Contract c : toExpire) {
            activeContracts.remove(c.getId());
            c.setStatus(ContractStatus.EXPIRED);

            plugin.getDatabaseManager().updateContract(c);

            plugin.getDatabaseManager().insertMail(
                    c.getContractorUUID(),
                    c.getReward(),
                    "Expired contract #" + c.getId() + " (" + c.getType().name() + ")",
                    null
            );
        }

        if (!toExpire.isEmpty()) {
            plugin.getLogger().info("[ContractBoard] Expired " + toExpire.size() + " contract(s).");
        }
    }

    // ---- Contract Creation ----

    public void createContract(Player contractor, ContractType type, double reward,
                               String metadata, Consumer<Contract> callback) {
        ConfigManager cfg = plugin.getConfigManager();

        // 1. Feature toggle
        boolean enabled = switch (type) {
            case BOUNTY_HUNT -> cfg.isBountyEnabled();
            case ITEM_GATHERING -> cfg.isItemGatheringEnabled();
            case XP_SERVICE -> cfg.isXPServiceEnabled();
        };
        if (!enabled) {
            contractor.sendMessage(stripColor(cfg.getMessage("feature-disabled")));
            return;
        }

        // 2. Price validation
        if (reward < cfg.getMinPrice(type)) {
            contractor.sendMessage(stripColor(cfg.getMessage("contract.reward-too-low")
                    .replace("{min}", String.valueOf(cfg.getMinPrice(type)))));
            return;
        }
        if (reward > cfg.getMaxPrice(type)) {
            contractor.sendMessage(stripColor(cfg.getMessage("contract.reward-too-high")
                    .replace("{max}", String.valueOf(cfg.getMaxPrice(type)))));
            return;
        }

        // 3. Contract limit check
        int activeOwned = countActiveContractsByContractor(contractor.getUniqueId());
        int limit = cfg.getContractLimit(contractor);
        if (activeOwned >= limit) {
            contractor.sendMessage(stripColor(cfg.getMessage("contract.limit-reached")
                    .replace("{limit}", limit == Integer.MAX_VALUE ? "unlimited" : String.valueOf(limit))));
            return;
        }

        // 4. Economy check
        if (!plugin.hasEconomy()) {
            contractor.sendMessage(stripColor(cfg.getMessage("economy-not-found")));
            return;
        }

        double taxRate = cfg.getTaxRate(type);
        double tax = reward * (taxRate / 100.0);
        double totalCost = reward + tax;

        if (!plugin.getEconomy().has(contractor, totalCost)) {
            contractor.sendMessage(stripColor(cfg.getMessage("contract.insufficient-funds")
                    .replace("{amount}", String.format("%.2f", totalCost))));
            return;
        }

        // 5. Deduct money
        plugin.getEconomy().withdrawPlayer(contractor, totalCost);

        // 6. Build and persist contract
        long now = System.currentTimeMillis();
        long expires = now + cfg.getExpirationMillis(type);

        Contract contract = new Contract(-1, type,
                contractor.getUniqueId(), contractor.getName(),
                reward, tax, now, expires, metadata);

        plugin.getDatabaseManager().insertContract(contract, inserted -> {
            if (inserted == null) {
                // DB failure — refund everything
                plugin.getEconomy().depositPlayer(contractor, totalCost);
                contractor.sendMessage(stripColor(cfg.colorize("&cFailed to create contract. Refunded.")));
                return;
            }

            // Add to cache
            activeContracts.put(inserted.getId(), inserted);

            // Update leaderboard stats
            plugin.getLeaderboardManager().recordSpent(
                    contractor.getUniqueId(), contractor.getName(), totalCost);

            // Confirm to player
            contractor.sendMessage(stripColor(cfg.getMessage("contract.created")
                    .replace("{id}", String.valueOf(inserted.getId()))
                    .replace("{reward}", String.format("%.2f", reward))
                    .replace("{tax}", String.format("%.2f", tax))));

            // ANNOUNCEMENT: Broadcast to all players
            broadcastAnnouncement(inserted, contractor);

            if (callback != null) callback.accept(inserted);
        });
    }

    /**
     * Broadcasts an announcement when a new contract is created
     * ADDED: Announcement system with customizable messages
     */
    private void broadcastAnnouncement(Contract contract, Player contractor) {
        ConfigManager cfg = plugin.getConfigManager();

        // Check if announcements are enabled globally
        if (!cfg.getConfig().getBoolean("announcements.enabled", true)) {
            return;
        }

        String path = switch (contract.getType()) {
            case BOUNTY_HUNT -> "announcements.bounty-hunt";
            case ITEM_GATHERING -> "announcements.item-gathering";
            case XP_SERVICE -> "announcements.xp-services";
        };

        // Check if this specific type has announcements enabled
        if (!cfg.getConfig().getBoolean(path + ".enabled", true)) {
            return;
        }

        List<String> messages = cfg.getConfig().getStringList(path + ".messages");
        if (messages.isEmpty()) return;

        // Replace placeholders based on contract type
        List<String> finalMessages = new ArrayList<>();
        for (String msg : messages) {
            msg = msg.replace("{contractor}", contractor.getName());
            msg = msg.replace("{reward}", String.format("%.2f", contract.getReward()));
            msg = msg.replace("{id}", String.valueOf(contract.getId()));

            // Type-specific placeholders
            switch (contract.getType()) {
                case BOUNTY_HUNT -> {
                    String target = MetadataUtil.getBountyTargetName(contract.getMetadata());
                    boolean anon = MetadataUtil.isBountyAnonymous(contract.getMetadata());
                    msg = msg.replace("{target}", target);
                    msg = msg.replace("{anonymous}", anon ? "Yes" : "No");
                }
                case ITEM_GATHERING -> {
                    String material = MetadataUtil.getItemMaterial(contract.getMetadata());
                    int amount = MetadataUtil.getItemAmount(contract.getMetadata());
                    msg = msg.replace("{item}", formatMaterial(material));
                    msg = msg.replace("{amount}", String.valueOf(amount));
                }
                case XP_SERVICE -> {
                    int points = MetadataUtil.getXPPoints(contract.getMetadata());
                    String mode = MetadataUtil.getXPMode(contract.getMetadata());
                    msg = msg.replace("{xp_points}", String.valueOf(points));
                    msg = msg.replace("{mode}", mode.replace("_", " "));
                }
            }

            finalMessages.add(cfg.colorize(msg));
        }

        // Broadcast to all online players
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (String msg : finalMessages) {
                player.sendMessage(msg);
            }
        }
    }

    /**
     * Formats a material name for display (e.g., DIAMOND_SWORD -> Diamond Sword)
     */
    private String formatMaterial(String material) {
        try {
            Material mat = Material.valueOf(material);
            String[] words = mat.name().toLowerCase().split("_");
            StringBuilder result = new StringBuilder();
            for (String word : words) {
                if (result.length() > 0) result.append(" ");
                result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            return result.toString();
        } catch (IllegalArgumentException e) {
            return material;
        }
    }

    /**
     * Strips color codes for clean output
     */
    private String stripColor(String message) {
        return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', message));
    }

    // ---- Contract Acceptance ----

    public void acceptContract(Player worker, int contractId) {
        Contract contract = activeContracts.get(contractId);
        if (contract == null) {
            worker.sendMessage(stripColor(plugin.getConfigManager().getMessage("contract.not-found")));
            return;
        }
        if (contract.getStatus() != ContractStatus.OPEN) {
            worker.sendMessage(stripColor(plugin.getConfigManager().getMessage("contract.already-accepted")));
            return;
        }
        if (contract.getContractorUUID().equals(worker.getUniqueId())) {
            worker.sendMessage(stripColor(plugin.getConfigManager().getMessage("contract.cannot-self")));
            return;
        }

        contract.setWorker(worker.getUniqueId(), worker.getName());
        contract.setStatus(ContractStatus.ACCEPTED);
        plugin.getDatabaseManager().updateContract(contract);

        worker.sendMessage(stripColor(plugin.getConfigManager().getMessage("contract.accepted")
                .replace("{id}", String.valueOf(contractId))));
    }

    // ---- Contract Cancellation ----

    public void cancelContract(Player player, int contractId) {
        Contract contract = activeContracts.get(contractId);
        if (contract == null || !contract.getContractorUUID().equals(player.getUniqueId())) {
            player.sendMessage(stripColor(plugin.getConfigManager().getMessage("contract.not-found")));
            return;
        }

        activeContracts.remove(contractId);
        contract.setStatus(ContractStatus.CANCELLED);
        plugin.getDatabaseManager().updateContract(contract);

        // Refund reward only (tax stays consumed)
        plugin.getDatabaseManager().insertMail(
                player.getUniqueId(),
                contract.getReward(),
                "Cancelled contract #" + contractId + " refund",
                null
        );

        player.sendMessage(stripColor(plugin.getConfigManager().getMessage("contract.cancelled")
                .replace("{id}", String.valueOf(contractId))));
    }

    // ---- Contract Completion ----

    public void completeContract(Contract contract, Player worker) {
        activeContracts.remove(contract.getId());
        contract.setStatus(ContractStatus.COMPLETED);
        plugin.getDatabaseManager().updateContract(contract);

        // Pay worker the full reward
        if (plugin.hasEconomy()) {
            plugin.getEconomy().depositPlayer(worker, contract.getReward());
        }

        // Update leaderboard
        plugin.getLeaderboardManager().recordEarned(
                worker.getUniqueId(), worker.getName(), contract.getReward());

        worker.sendMessage(stripColor(plugin.getConfigManager().getMessage("contract.completed")
                .replace("{id}", String.valueOf(contract.getId()))
                .replace("{reward}", String.format("%.2f", contract.getReward()))));
    }

    // ---- Queries ----

    public Map<Integer, Contract> getActiveContracts() {
        return Collections.unmodifiableMap(activeContracts);
    }

    public Optional<Contract> getContract(int id) {
        return Optional.ofNullable(activeContracts.get(id));
    }

    public List<Contract> getOpenContracts(ContractType type) {
        return activeContracts.values().stream()
                .filter(c -> c.getType() == type && c.getStatus() == ContractStatus.OPEN)
                .sorted(Comparator.comparingLong(Contract::getCreatedAt).reversed())
                .toList();
    }

    public List<Contract> getContractsByWorker(UUID workerUUID) {
        return activeContracts.values().stream()
                .filter(c -> workerUUID.equals(c.getWorkerUUID()))
                .toList();
    }

    public int countActiveContractsByContractor(UUID contractorUUID) {
        return (int) activeContracts.values().stream()
                .filter(c -> c.isActive() && c.getContractorUUID().equals(contractorUUID))
                .count();
    }
}