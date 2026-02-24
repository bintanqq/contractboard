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
import java.util.function.Consumer;

/**
 * Central manager for all contract lifecycle operations.
 *
 * Tax model:
 *   Contractor pays reward + tax at creation time.
 *   Worker always receives the full reward.
 *   Tax is a permanent money sink — not refunded on cancel.
 *   On cancel: only the reward (not tax) is sent to mail.
 *
 * Contract limits:
 *   Checked against player permissions / config before accepting payment.
 *   Counts OPEN + ACCEPTED + PAUSED contracts owned by the contractor.
 *
 * Expiration:
 *   Runs on a SINGLE scheduled async task every 30 seconds.
 *   Expired contracts are processed in a batch: all DB writes for that
 *   batch are submitted to the DB executor sequentially (no nested schedulers).
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

    /**
     * Starts the periodic expiration checker.
     * Runs fully async (no main-thread involvement unless economy/mail callbacks need it).
     * 600 ticks = 30 seconds between checks.
     */
    public void startExpirationTask() {
        expirationTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::processExpiredContracts, 1200L, 600L);
    }

    /**
     * Scans for expired contracts and handles them.
     * Called from the async expiration thread — safe to call DB methods directly
     * (DB executor is its own thread; we just submit work to it).
     */
    private void processExpiredContracts() {
        long now = System.currentTimeMillis();

        // Snapshot the entries to expire to avoid ConcurrentModificationException
        List<Contract> toExpire = activeContracts.values().stream()
                .filter(c -> c.isActive() && c.getExpiresAt() < now)
                .toList();

        if (toExpire.isEmpty()) return;

        for (Contract c : toExpire) {
            // Remove from cache immediately so no new operations target this contract
            activeContracts.remove(c.getId());
            c.setStatus(ContractStatus.EXPIRED);

            // Persist status change
            plugin.getDatabaseManager().updateContract(c);

            // Send reward back to contractor via mail (no callback needed)
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

    /**
     * Creates and persists a contract.
     *
     * Tax model: contractor pays reward + tax.
     * Worker receives reward. Tax is a permanent sink.
     *
     * @param contractor The player creating the contract
     * @param type       Contract type
     * @param reward     Amount the worker will receive (NOT including tax)
     * @param metadata   Type-specific metadata string
     * @param callback   Called with the saved contract on success (main thread)
     */
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
            contractor.sendMessage(cfg.getMessage("feature-disabled"));
            return;
        }

        // 2. Price validation
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

        // 3. Contract limit check
        int activeOwned = countActiveContractsByContractor(contractor.getUniqueId());
        int limit = cfg.getContractLimit(contractor);
        if (activeOwned >= limit) {
            contractor.sendMessage(cfg.getMessage("contract.limit-reached")
                    .replace("{limit}", limit == Integer.MAX_VALUE ? "unlimited" : String.valueOf(limit)));
            return;
        }

        // 4. Economy check — contractor pays reward + tax
        if (!plugin.hasEconomy()) {
            contractor.sendMessage(cfg.getMessage("economy-not-found"));
            return;
        }

        double taxRate = cfg.getTaxRate(type);
        double tax = reward * (taxRate / 100.0);
        double totalCost = reward + tax; // reward → escrow, tax → permanent sink

        if (!plugin.getEconomy().has(contractor, totalCost)) {
            contractor.sendMessage(cfg.getMessage("contract.insufficient-funds")
                    .replace("{amount}", String.format("%.2f", totalCost)));
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
                contractor.sendMessage(cfg.colorize("&cFailed to create contract. Refunded."));
                return;
            }

            // Add to cache on main thread (safe)
            activeContracts.put(inserted.getId(), inserted);

            // Update leaderboard stats
            plugin.getLeaderboardManager().recordSpent(
                    contractor.getUniqueId(), contractor.getName(), totalCost);

            // Confirm to player
            contractor.sendMessage(cfg.getMessage("contract.created")
                    .replace("{id}", String.valueOf(inserted.getId()))
                    .replace("{reward}", String.format("%.2f", reward))
                    .replace("{tax}", String.format("%.2f", tax)));

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

    /**
     * Cancels a contract. Only the contractor can cancel.
     * Tax is NOT refunded. Reward is sent to contractor's mail.
     */
    public void cancelContract(Player player, int contractId) {
        Contract contract = activeContracts.get(contractId);
        if (contract == null || !contract.getContractorUUID().equals(player.getUniqueId())) {
            player.sendMessage(plugin.getConfigManager().getMessage("contract.not-found"));
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

        player.sendMessage(plugin.getConfigManager().getMessage("contract.cancelled")
                .replace("{id}", String.valueOf(contractId)));
    }

    // ---- Contract Completion ----

    /**
     * Marks a contract complete and pays the worker the full reward.
     * Must be called on the main thread (economy API requirement).
     */
    public void completeContract(Contract contract, Player worker) {
        activeContracts.remove(contract.getId());
        contract.setStatus(ContractStatus.COMPLETED);
        plugin.getDatabaseManager().updateContract(contract);

        // Pay worker the full reward (tax was already paid by contractor at creation)
        if (plugin.hasEconomy()) {
            plugin.getEconomy().depositPlayer(worker, contract.getReward());
        }

        // Update leaderboard
        plugin.getLeaderboardManager().recordEarned(
                worker.getUniqueId(), worker.getName(), contract.getReward());

        worker.sendMessage(plugin.getConfigManager().getMessage("contract.completed")
                .replace("{id}", String.valueOf(contract.getId()))
                .replace("{reward}", String.format("%.2f", contract.getReward())));
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

    /**
     * Counts active (OPEN/ACCEPTED/PAUSED) contracts where this UUID is the contractor.
     */
    public int countActiveContractsByContractor(UUID contractorUUID) {
        return (int) activeContracts.values().stream()
                .filter(c -> c.isActive() && c.getContractorUUID().equals(contractorUUID))
                .count();
    }
}