package me.bintanq.model;

import java.util.UUID;

/**
 * Tracks per-player leaderboard statistics.
 */
public class PlayerStats {

    private final UUID uuid;
    private final String name;
    private double totalSpent;   // As contractor
    private double totalEarned;  // As laborer
    private int contractsPosted;
    private int contractsCompleted;

    public PlayerStats(UUID uuid, String name, double totalSpent, double totalEarned,
                       int contractsPosted, int contractsCompleted) {
        this.uuid = uuid;
        this.name = name;
        this.totalSpent = totalSpent;
        this.totalEarned = totalEarned;
        this.contractsPosted = contractsPosted;
        this.contractsCompleted = contractsCompleted;
    }

    public UUID getUuid() { return uuid; }
    public String getName() { return name; }
    public double getTotalSpent() { return totalSpent; }
    public double getTotalEarned() { return totalEarned; }
    public int getContractsPosted() { return contractsPosted; }
    public int getContractsCompleted() { return contractsCompleted; }

    public void addSpent(double amount) { this.totalSpent += amount; }
    public void addEarned(double amount) { this.totalEarned += amount; }
    public void incrementPosted() { this.contractsPosted++; }
    public void incrementCompleted() { this.contractsCompleted++; }
}