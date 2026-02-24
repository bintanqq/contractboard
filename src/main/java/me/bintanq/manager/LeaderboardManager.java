package me.bintanq.manager;

import me.bintanq.ContractBoard;
import me.bintanq.model.PlayerStats;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches leaderboard data in memory and periodically refreshes from DB.
 * Supports PlaceholderAPI placeholders.
 */
public class LeaderboardManager {

    private final ContractBoard plugin;

    private final Map<UUID, PlayerStats> statsCache = new ConcurrentHashMap<>();
    private List<PlayerStats> topContractors = new ArrayList<>();
    private List<PlayerStats> topLaborers = new ArrayList<>();

    private static final int TOP_SIZE = 10;

    public LeaderboardManager(ContractBoard plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.getDatabaseManager().getTopBySpent(TOP_SIZE, list -> topContractors = list);
        plugin.getDatabaseManager().getTopByEarned(TOP_SIZE, list -> topLaborers = list);
    }

    public void addSpent(UUID uuid, String name, double amount) {
        PlayerStats stats = statsCache.computeIfAbsent(uuid,
                id -> new PlayerStats(id, name, 0, 0, 0, 0));
        stats.addSpent(amount);
        stats.incrementPosted();
        plugin.getDatabaseManager().upsertPlayerStats(stats);
        refreshLeaderboards();
    }

    public void addEarned(UUID uuid, String name, double amount) {
        PlayerStats stats = statsCache.computeIfAbsent(uuid,
                id -> new PlayerStats(id, name, 0, 0, 0, 0));
        stats.addEarned(amount);
        stats.incrementCompleted();
        plugin.getDatabaseManager().upsertPlayerStats(stats);
        refreshLeaderboards();
    }

    private void refreshLeaderboards() {
        plugin.getDatabaseManager().getTopBySpent(TOP_SIZE, list -> topContractors = list);
        plugin.getDatabaseManager().getTopByEarned(TOP_SIZE, list -> topLaborers = list);
    }

    // ---- Getters for GUI & PAPI ----

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

    public void getPlayerStats(UUID uuid, java.util.function.Consumer<PlayerStats> callback) {
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
