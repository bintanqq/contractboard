package me.bintanq.manager;

import me.bintanq.ContractBoard;
import me.bintanq.model.PlayerStats;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Caches leaderboard data and player stats in memory.
 *
 * Performance fix:
 *   The original code called refreshLeaderboards() (2 DB reads) every time a
 *   contract was created or completed. On busy servers this floods the DB executor
 *   queue. Instead we use a DEBOUNCE: schedule a refresh 5 seconds after the last
 *   activity. Multiple rapid completions collapse into one DB read.
 */
public class LeaderboardManager {

    private final ContractBoard plugin;

    // In-memory leaderboard snapshots (updated from DB periodically)
    private volatile List<PlayerStats> topContractors = new ArrayList<>();
    private volatile List<PlayerStats> topLaborers = new ArrayList<>();

    // Per-player stats cache (avoids DB lookup for PAPI placeholders)
    private final Map<UUID, PlayerStats> statsCache = new ConcurrentHashMap<>();

    private static final int TOP_SIZE = 10;

    // Debounce: task that fires 5 seconds after the last activity
    private BukkitTask pendingRefresh = null;

    public LeaderboardManager(ContractBoard plugin) {
        this.plugin = plugin;
        reload();
    }

    /** Hard reload — fetches from DB immediately. Called on /contract reload. */
    public void reload() {
        plugin.getDatabaseManager().getTopBySpent(TOP_SIZE, list -> topContractors = list);
        plugin.getDatabaseManager().getTopByEarned(TOP_SIZE, list -> topLaborers = list);
    }

    // ---- Stats Recording ----

    /** Called when a contractor posts (or loses money to) a contract. */
    public void recordSpent(UUID uuid, String name, double amount) {
        PlayerStats stats = statsCache.computeIfAbsent(uuid,
                id -> new PlayerStats(id, name, 0, 0, 0, 0));
        stats.addSpent(amount);
        stats.incrementPosted();
        persistStats(stats);
        scheduleRefresh();
    }

    /** Called when a worker completes a contract and receives payment. */
    public void recordEarned(UUID uuid, String name, double amount) {
        PlayerStats stats = statsCache.computeIfAbsent(uuid,
                id -> new PlayerStats(id, name, 0, 0, 0, 0));
        stats.addEarned(amount);
        stats.incrementCompleted();
        persistStats(stats);
        scheduleRefresh();
    }

    /** Submits a DB upsert for the given stats object (snapshots fields to avoid races). */
    private void persistStats(PlayerStats stats) {
        plugin.getDatabaseManager().upsertPlayerStats(
                stats.getUuid(), stats.getName(),
                stats.getTotalSpent(), stats.getTotalEarned(),
                stats.getContractsPosted(), stats.getContractsCompleted()
        );
    }

    /**
     * Schedules a leaderboard refresh 5 seconds from now.
     * If one is already pending, it gets reset (debounce behaviour).
     * Must be called on the main thread.
     */
    private void scheduleRefresh() {
        if (pendingRefresh != null && !pendingRefresh.isCancelled()) {
            pendingRefresh.cancel();
        }
        pendingRefresh = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            plugin.getDatabaseManager().getTopBySpent(TOP_SIZE, list -> topContractors = list);
            plugin.getDatabaseManager().getTopByEarned(TOP_SIZE, list -> topLaborers = list);
            pendingRefresh = null;
        }, 100L); // 100 ticks = 5 seconds
    }

    // ---- Getters ----

    public List<PlayerStats> getTopContractors() { return topContractors; }
    public List<PlayerStats> getTopLaborers() { return topLaborers; }

    public String getTopContractorName(int rank) {
        if (rank < 1 || rank > topContractors.size()) return "N/A";
        return topContractors.get(rank - 1).getName();
    }

    public double getTopContractorAmount(int rank) {
        if (rank < 1 || rank > topContractors.size()) return 0;
        return topContractors.get(rank - 1).getTotalSpent();
    }

    public String getTopLaborerName(int rank) {
        if (rank < 1 || rank > topLaborers.size()) return "N/A";
        return topLaborers.get(rank - 1).getName();
    }

    public double getTopLaborerAmount(int rank) {
        if (rank < 1 || rank > topLaborers.size()) return 0;
        return topLaborers.get(rank - 1).getTotalEarned();
    }

    /**
     * Returns player stats via callback.
     * Hits the cache first; falls back to DB if not cached (e.g. first login).
     */
    public void getPlayerStats(UUID uuid, Consumer<PlayerStats> callback) {
        PlayerStats cached = statsCache.get(uuid);
        if (cached != null) {
            callback.accept(cached);
        } else {
            plugin.getDatabaseManager().getPlayerStats(uuid, stats -> {
                if (stats != null) statsCache.put(uuid, stats);
                callback.accept(stats);
            });
        }
    }
}