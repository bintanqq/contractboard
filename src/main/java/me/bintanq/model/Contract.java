package me.bintanq.model;

import java.util.UUID;

/**
 * Base representation of a Contract.
 * All contract types extend or embed this data.
 */
public class Contract {

    // ---- Enums ----

    public enum ContractType {
        BOUNTY_HUNT,
        ITEM_GATHERING,
        XP_SERVICE
    }

    public enum ContractStatus {
        OPEN,       // Posted, awaiting a worker
        ACCEPTED,   // Worker accepted, in progress
        PAUSED,     // Bounty target offline
        COMPLETED,  // Work done, reward paid
        CANCELLED,  // Cancelled by contractor or admin
        EXPIRED     // Passed expiration time
    }

    // ---- Fields ----

    private final int id;
    private final ContractType type;
    private final UUID contractorUUID;
    private final String contractorName;
    private UUID workerUUID;
    private String workerName;
    private ContractStatus status;
    private final double reward;       // Net reward (after tax deducted at creation)
    private final double taxPaid;
    private final long createdAt;      // Unix millis
    private final long expiresAt;      // Unix millis

    // Type-specific metadata stored as serialized JSON/string in DB
    private String metadata;

    public Contract(int id, ContractType type, UUID contractorUUID, String contractorName,
                    double reward, double taxPaid, long createdAt, long expiresAt, String metadata) {
        this.id = id;
        this.type = type;
        this.contractorUUID = contractorUUID;
        this.contractorName = contractorName;
        this.reward = reward;
        this.taxPaid = taxPaid;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.metadata = metadata;
        this.status = ContractStatus.OPEN;
    }

    // ---- Getters & Setters ----

    public int getId() { return id; }
    public ContractType getType() { return type; }
    public UUID getContractorUUID() { return contractorUUID; }
    public String getContractorName() { return contractorName; }
    public UUID getWorkerUUID() { return workerUUID; }
    public String getWorkerName() { return workerName; }
    public ContractStatus getStatus() { return status; }
    public double getReward() { return reward; }
    public double getTaxPaid() { return taxPaid; }
    public long getCreatedAt() { return createdAt; }
    public long getExpiresAt() { return expiresAt; }
    public String getMetadata() { return metadata; }

    public void setWorker(UUID uuid, String name) {
        this.workerUUID = uuid;
        this.workerName = name;
    }

    public void setStatus(ContractStatus status) { this.status = status; }
    public void setMetadata(String metadata) { this.metadata = metadata; }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }

    public boolean isActive() {
        return status == ContractStatus.OPEN || status == ContractStatus.ACCEPTED || status == ContractStatus.PAUSED;
    }
}