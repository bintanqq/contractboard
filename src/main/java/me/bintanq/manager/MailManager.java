package me.bintanq.manager;

import me.bintanq.ContractBoard;
import me.bintanq.model.MailEntry;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Handles the "Collection Mail" system.
 * Players can claim currency sent to them via /contract mail.
 */
public class MailManager {

    private final ContractBoard plugin;

    public MailManager(ContractBoard plugin) {
        this.plugin = plugin;
    }

    /**
     * Displays and collects all mail for the given player.
     * Called from /contract mail command or GUI.
     */
    public void collectAllMail(Player player) {
        plugin.getDatabaseManager().getMailForPlayer(player.getUniqueId(), entries -> {
            if (entries.isEmpty()) {
                player.sendMessage(plugin.getConfigManager().getMessage("mail.empty"));
                return;
            }

            double total = 0;
            for (MailEntry entry : entries) {
                total += entry.getAmount();
            }

            if (plugin.hasEconomy()) {
                plugin.getEconomy().depositPlayer(player, total);
            }

            plugin.getDatabaseManager().deleteAllMailForPlayer(player.getUniqueId());

            player.sendMessage(plugin.getConfigManager().getMessage("mail.collected")
                    .replace("{amount}", String.format("%.2f", total)));
        });
    }

    /**
     * Lists mail entries for the GUI.
     */
    public void getMailEntries(Player player, java.util.function.Consumer<List<MailEntry>> callback) {
        plugin.getDatabaseManager().getMailForPlayer(player.getUniqueId(), callback);
    }

    /**
     * Sends a specific amount to a player's mail (called internally).
     */
    public void sendToMail(java.util.UUID recipientUUID, double amount, String reason) {
        plugin.getDatabaseManager().insertMail(recipientUUID, amount, reason, null);
    }
}