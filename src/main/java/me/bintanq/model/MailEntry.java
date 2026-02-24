package me.bintanq.model;

import java.util.UUID;

/**
 * Represents a pending mail entry for expired or completed contract rewards.
 */
public class MailEntry {

    private final int id;
    private final UUID recipientUUID;
    private final double amount;
    private final String description;
    private final long createdAt;

    public MailEntry(int id, UUID recipientUUID, double amount, String description, long createdAt) {
        this.id = id;
        this.recipientUUID = recipientUUID;
        this.amount = amount;
        this.description = description;
        this.createdAt = createdAt;
    }

    public int getId() { return id; }
    public UUID getRecipientUUID() { return recipientUUID; }
    public double getAmount() { return amount; }
    public String getDescription() { return description; }
    public long getCreatedAt() { return createdAt; }
}