package me.bintanq.placeholder;

import me.bintanq.ContractBoard;
import me.bintanq.model.PlayerStats;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI expansion for ContractBoard.
 *
 * Available placeholders:
 *   %contractboard_completed%             - Contracts completed by the player
 *   %contractboard_earned%                - Total earned by the player
 *   %contractboard_spent%                 - Total spent by the player
 *   %contractboard_posted%                - Contracts posted by the player
 *   %contractboard_top_contractor_name_1% - Name of top contractor (1-10)
 *   %contractboard_top_contractor_amt_1%  - Amount spent by top contractor
 *   %contractboard_top_laborer_name_1%    - Name of top laborer
 *   %contractboard_top_laborer_amt_1%     - Amount earned by top laborer
 */
public class ContractBoardExpansion extends PlaceholderExpansion {

    private final ContractBoard plugin;

    public ContractBoardExpansion(ContractBoard plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() { return "contractboard"; }

    @Override
    public @NotNull String getAuthor() { return "ContractBoard"; }

    @Override
    public @NotNull String getVersion() { return plugin.getDescription().getVersion(); }

    @Override
    public boolean persist() { return true; } // Don't unregister on PAPI reload

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        params = params.toLowerCase();

        // ---- Top Contractor/Laborer Placeholders ----
        // Pattern: top_contractor_name_<rank> / top_contractor_amt_<rank>
        if (params.startsWith("top_contractor_name_")) {
            try {
                int rank = Integer.parseInt(params.replace("top_contractor_name_", ""));
                return plugin.getLeaderboardManager().getTopContractorName(rank);
            } catch (NumberFormatException e) { return "N/A"; }
        }

        if (params.startsWith("top_contractor_amt_")) {
            try {
                int rank = Integer.parseInt(params.replace("top_contractor_amt_", ""));
                return String.format("%.2f", plugin.getLeaderboardManager().getTopContractorAmount(rank));
            } catch (NumberFormatException e) { return "0"; }
        }

        if (params.startsWith("top_laborer_name_")) {
            try {
                int rank = Integer.parseInt(params.replace("top_laborer_name_", ""));
                return plugin.getLeaderboardManager().getTopLaborerName(rank);
            } catch (NumberFormatException e) { return "N/A"; }
        }

        if (params.startsWith("top_laborer_amt_")) {
            try {
                int rank = Integer.parseInt(params.replace("top_laborer_amt_", ""));
                return String.format("%.2f", plugin.getLeaderboardManager().getTopLaborerAmount(rank));
            } catch (NumberFormatException e) { return "0"; }
        }

        // ---- Per-Player Placeholders ----
        if (player == null) return "";

        // These are cached or fetched async; return cached if available
        java.util.UUID uuid = player.getUniqueId();
        PlayerStats stats = getStatsSync(uuid, player.getName() != null ? player.getName() : "Unknown");

        return switch (params) {
            case "completed" -> String.valueOf(stats.getContractsCompleted());
            case "earned" -> String.format("%.2f", stats.getTotalEarned());
            case "spent" -> String.format("%.2f", stats.getTotalSpent());
            case "posted" -> String.valueOf(stats.getContractsPosted());
            default -> null;
        };
    }

    /**
     * Returns a stats object synchronously from cache, or a blank one if not cached.
     * Stats are loaded asynchronously on first player login/action.
     */
    private PlayerStats getStatsSync(java.util.UUID uuid, String name) {
        // Try to get from cache (LeaderboardManager keeps a live cache)
        final PlayerStats[] result = { new PlayerStats(uuid, name, 0, 0, 0, 0) };
        // Since we're on the main thread (PAPI requests are sync), we use the cached data
        plugin.getLeaderboardManager().getPlayerStats(uuid, stats -> {
            if (stats != null) result[0] = stats;
        });
        return result[0];
    }
}
