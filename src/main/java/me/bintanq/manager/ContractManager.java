package me.bintanq.manager;

import me.bintanq.ContractBoard;
import me.bintanq.model.Contract;
import me.bintanq.model.Contract.ContractStatus;
import me.bintanq.model.Contract.ContractType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central manager for all contract operations.
 * Holds the in-memory cache of active contracts and handles their lifecycle.
 */
public class ContractManager {

    private final ContractBoard plugin;

    // In-memory cache: contractId -> Contract
    private final Map<Integer, Contract> activeContracts = new ConcurrentHashMap<>();

    private BukkitTask expirationTask;

    public ContractManager(ContractBoard plugin) {
        this.plugin = plugin;
        loadContracts();
    }

    // ---- Initialization ----

    private void loadContracts() {
        plugin.getDatabaseManager().loadActiveContracts(contracts -> {
            for (Contract c : contracts) {
                activeContracts.put(c.getId(), c);
            }
            plugin.getLogger().info("Loaded " + contracts.size() + " active contracts.");
        });
    }

    // ---- Expiration Task ----

    public void startExpirationTask() {
        // Check every 60 seconds for expired contracts
        expirationTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::checkExpired, 1200L, 1200L);
    }

    private void checkExpired() {
        long now = System.currentTimeMillis();
        List<Contract> toExpire = activeContracts.values().stream()
                .filter(c -> c.isActive() && c.getExpiresAt() < now)
                .toList();

        for (Contract c : toExpire) {
            expireContract(c);
        }
    }

    private void expireContract(Contract contract) {
        contract.setStatus(ContractStatus.EXPIRED);
        activeContracts.remove(contract.getId());
        plugin.getDatabaseManager().updateContract(contract);

        // Send reward to contractor's mail
        plugin.getDatabaseManager().insertMail(
                contract.getContractorUUID(),
                contract.getReward(),
                "Expired contract #" + contract.getId() + " (" + contract.getType().name() + ")",
                null
        );

        plugin.getLogger().info("Contract #" + contract.getId() + " expired, reward sent to mail.");
    }

    // ---- Contract Creation ----

    /**
     * Creates and persists a new contract after deducting the reward + tax from the contractor.
     * Returns the contract via callback on the main thread.
     */
    public void createContract(Player contractor, ContractType type, double reward, String metadata,
                               java.util.function.Consumer<Contract> callback) {
        ConfigManager cfg = plugin.getConfigManager();

        // Check feature enabled
        boolean enabled = switch (type) {
            case BOUNTY_HUNT -> cfg.isBountyEnabled();
            case ITEM_GATHERING -> cfg.isItemGatheringEnabled();
            case XP_SERVICE -> cfg.isXPServiceEnabled();
        };

        if (!enabled) {
            contractor.sendMessage(cfg.getMessage("feature-disabled"));
            return;
        }

        // Validate price range
        if (reward < cfg.getMinPrice(type)) {
            contractor.sendMessage(cfg.getMessage("contract.reward-too-low")
                    .replace("{min}", String.valueOf(cfg.getMinPrice(type))));
            return;
        }
        if (reward > cfg.getMaxPrice(type)) {
            contractor.sendMessage(cfg.getMessage("contract.reward-too-high")
                    .replace("{max}", String.valueOf(cfg.getMaxPrice(type))));
            return;
        }

        double taxRate = cfg.getTaxRate(type);
        double tax = reward * (taxRate / 100.0);
        double totalCost = reward + tax; // reward is held in escrow, tax is consumed

        // Check economy
        if (!plugin.hasEconomy()) {
            contractor.sendMessage(cfg.getMessage("economy-not-found"));
            return;
        }

        if (!plugin.getEconomy().has(contractor, totalCost)) {
            contractor.sendMessage(cfg.getMessage("contract.insufficient-funds")
                    .replace("{amount}", String.format("%.2f", totalCost)));
            return;
        }

        // Deduct total cost (reward goes into escrow conceptually, tax is a sink)
        plugin.getEconomy().withdrawPlayer(contractor, totalCost);

        // Build contract object
        long now = System.currentTimeMillis();
        long expires = now + cfg.getExpirationMillis(type);

        Contract contract = new Contract(
                -1, type,
                contractor.getUniqueId(), contractor.getName(),
                reward, tax,
                now, expires, metadata
        );

        // Persist and get ID back
        plugin.getDatabaseManager().insertContract(contract, inserted -> {
            if (inserted == null) {
                // Refund on DB failure
                plugin.getEconomy().depositPlayer(contractor, totalCost);
                contractor.sendMessage(cfg.getMessage("contract.not-found")); // generic error
                return;
            }
            activeContracts.put(inserted.getId(), inserted);

            // Update stats
            plugin.getLeaderboardManager().addSpent(contractor.getUniqueId(), contractor.getName(), totalCost);

            String msg = cfg.getMessage("contract.created")
                    .replace("{id}", String.valueOf(inserted.getId()))
                    .replace("{tax}", String.format("%.2f", tax));
            contractor.sendMessage(msg);

            if (callback != null) callback.accept(inserted);
        });
    }

    // ---- Contract Acceptance ----

    public void acceptContract(Player worker, int contractId) {
        Contract contract = activeContracts.get(contractId);
        if (contract == null) {
            worker.sendMessage(plugin.getConfigManager().getMessage("contract.not-found"));
            return;
        }

        if (contract.getStatus() != ContractStatus.OPEN) {
            worker.sendMessage(plugin.getConfigManager().getMessage("contract.already-accepted"));
            return;
        }

        if (contract.getContractorUUID().equals(worker.getUniqueId())) {
            worker.sendMessage(plugin.getConfigManager().getMessage("contract.cannot-self"));
            return;
        }

        contract.setWorker(worker.getUniqueId(), worker.getName());
        contract.setStatus(ContractStatus.ACCEPTED);
        plugin.getDatabaseManager().updateContract(contract);

        worker.sendMessage(plugin.getConfigManager().getMessage("contract.accepted")
                .replace("{id}", String.valueOf(contractId)));
    }

    // ---- Contract Cancellation ----

    public void cancelContract(Player player, int contractId) {
        Contract contract = activeContracts.get(contractId);
        if (contract == null || !contract.getContractorUUID().equals(player.getUniqueId())) {
            player.sendMessage(plugin.getConfigManager().getMessage("contract.not-found"));
            return;
        }

        contract.setStatus(ContractStatus.CANCELLED);
        activeContracts.remove(contractId);
        plugin.getDatabaseManager().updateContract(contract);

        // Refund only the reward (tax was already consumed), send to mail in case offline
        plugin.getDatabaseManager().insertMail(player.getUniqueId(), contract.getReward(),
                "Cancelled contract #" + contractId + " reward refund", null);

        player.sendMessage(plugin.getConfigManager().getMessage("contract.cancelled")
                .replace("{id}", String.valueOf(contractId)));
    }

    // ---- Contract Completion ----

    public void completeContract(Contract contract, Player worker) {
        contract.setStatus(ContractStatus.COMPLETED);
        activeContracts.remove(contract.getId());
        plugin.getDatabaseManager().updateContract(contract);

        // Pay the worker
        if (plugin.hasEconomy()) {
            plugin.getEconomy().depositPlayer(worker, contract.getReward());
        }

        // Update stats
        plugin.getLeaderboardManager().addEarned(worker.getUniqueId(), worker.getName(), contract.getReward());

        worker.sendMessage(plugin.getConfigManager().getMessage("contract.completed")
                .replace("{id}", String.valueOf(contract.getId()))
                .replace("{reward}", String.format("%.2f", contract.getReward())));
    }

    // ---- Getters ----

    public Map<Integer, Contract> getActiveContracts() { return Collections.unmodifiableMap(activeContracts); }

    public Optional<Contract> getContract(int id) {
        return Optional.ofNullable(activeContracts.get(id));
    }

    /**
     * Get all open contracts of a given type for the GUI.
     */
    public List<Contract> getOpenContracts(ContractType type) {
        return activeContracts.values().stream()
                .filter(c -> c.getType() == type && c.getStatus() == ContractStatus.OPEN)
                .sorted(Comparator.comparingLong(Contract::getCreatedAt).reversed())
                .toList();
    }

    /**
     * Get contracts accepted by a specific worker.
     */
    public List<Contract> getContractsByWorker(UUID workerUUID) {
        return activeContracts.values().stream()
                .filter(c -> workerUUID.equals(c.getWorkerUUID()))
                .toList();
    }
}
