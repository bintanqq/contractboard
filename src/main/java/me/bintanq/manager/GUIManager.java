package me.bintanq.manager;

import me.bintanq.ContractBoard;
import me.bintanq.gui.GUIState;
import me.bintanq.model.Contract;
import me.bintanq.model.MailEntry;
import me.bintanq.model.PlayerStats;
import me.bintanq.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds and manages all plugin inventories.
 * Uses a State pattern — each open GUI is tracked per player with a {@link GUIState}.
 * The GUIListener routes clicks back here based on the player's current state.
 */
public class GUIManager {

    private final ContractBoard plugin;

    // playerUUID -> current GUI state
    private final Map<UUID, GUIState> playerStates = new HashMap<>();

    public GUIManager(ContractBoard plugin) {
        this.plugin = plugin;
    }

    // ---- State Management ----

    public void setState(UUID uuid, GUIState state) { playerStates.put(uuid, state); }
    public GUIState getState(UUID uuid) { return playerStates.getOrDefault(uuid, GUIState.NONE); }
    public void clearState(UUID uuid) { playerStates.remove(uuid); }
    public Map<UUID, GUIState> getAllStates() { return playerStates; }

    // ---- Open Main Board ----

    public void openMainBoard(Player player) {
        ConfigurationSection cfg = plugin.getConfigManager().getGuiConfig().getConfigurationSection("main-board");
        int rows = cfg.getInt("rows", 6);
        String title = colorize(cfg.getString("title", "Contract Board"));

        Inventory inv = Bukkit.createInventory(new CBInventoryHolder("MAIN_BOARD"), rows * 9, title);
        fillBackground(inv, cfg.getConfigurationSection("filler"));

        setGuiButton(inv, cfg, "buttons.bounty-hunt");
        setGuiButton(inv, cfg, "buttons.item-gathering");
        setGuiButton(inv, cfg, "buttons.xp-services");
        setGuiButton(inv, cfg, "buttons.leaderboard");
        setGuiButton(inv, cfg, "buttons.mail");
        setGuiButton(inv, cfg, "buttons.close");

        setState(player.getUniqueId(), GUIState.MAIN_BOARD);
        player.openInventory(inv);
    }

    // ---- Open Contract List ----

    public void openContractList(Player player, Contract.ContractType type, int page) {
        ConfigurationSection cfg = plugin.getConfigManager().getGuiConfig().getConfigurationSection("contract-list");
        int rows = cfg.getInt("rows", 6);
        String title = colorize(cfg.getString("title", "Contracts").replace("{type}", formatType(type)));

        Inventory inv = Bukkit.createInventory(new CBInventoryHolder("CONTRACT_LIST_" + type.name()), rows * 9, title);
        fillBackground(inv, cfg.getConfigurationSection("filler"));

        List<Contract> contracts = plugin.getContractManager().getOpenContracts(type);
        int pageSize = 45;
        int start = page * pageSize;
        int end = Math.min(start + pageSize, contracts.size());

        for (int i = start; i < end; i++) {
            Contract c = contracts.get(i);
            inv.setItem(i - start, buildContractIcon(c));
        }

        // Navigation buttons
        setGuiButton(inv, cfg, "navigation.back");
        setGuiButton(inv, cfg, "navigation.new-contract");
        if (page > 0) setGuiButton(inv, cfg, "navigation.prev-page");
        if (end < contracts.size()) setGuiButton(inv, cfg, "navigation.next-page");

        setState(player.getUniqueId(), GUIState.CONTRACT_LIST);
        player.openInventory(inv);
    }

    // ---- Open Contract Detail ----

    public void openContractDetail(Player player, Contract contract) {
        ConfigurationSection cfg = plugin.getConfigManager().getGuiConfig().getConfigurationSection("contract-detail");
        int rows = cfg.getInt("rows", 4);
        String title = colorize("Contract #" + contract.getId());

        Inventory inv = Bukkit.createInventory(new CBInventoryHolder("CONTRACT_DETAIL_" + contract.getId()), rows * 9, title);
        fillBackground(inv, cfg.getConfigurationSection("filler"));

        // Center display: contract info
        inv.setItem(13, buildContractIcon(contract));

        // Show action buttons based on player role
        boolean isContractor = player.getUniqueId().equals(contract.getContractorUUID());
        boolean isWorker = player.getUniqueId().equals(contract.getWorkerUUID());
        boolean isOpen = contract.getStatus() == Contract.ContractStatus.OPEN;
        boolean isAccepted = contract.getStatus() == Contract.ContractStatus.ACCEPTED;

        if (!isContractor && isOpen) setGuiButton(inv, cfg, "buttons.accept");
        if (isContractor && isOpen) setGuiButton(inv, cfg, "buttons.cancel");
        if (isWorker && isAccepted) setGuiButton(inv, cfg, "buttons.submit");
        setGuiButton(inv, cfg, "buttons.back");

        setState(player.getUniqueId(), GUIState.CONTRACT_DETAIL);
        player.openInventory(inv);
    }

    // ---- Leaderboard GUI ----

    public void openLeaderboard(Player player) {
        ConfigurationSection cfg = plugin.getConfigManager().getGuiConfig().getConfigurationSection("leaderboard");
        int rows = cfg.getInt("rows", 6);
        String title = colorize(cfg.getString("title", "Leaderboards"));

        Inventory inv = Bukkit.createInventory(new CBInventoryHolder("LEADERBOARD"), rows * 9, title);
        fillBackground(inv, cfg.getConfigurationSection("filler"));

        setGuiButton(inv, cfg, "buttons.back");

        // Display top contractors in left column (slots 10-18)
        List<PlayerStats> contractors = plugin.getLeaderboardManager().getTopContractors();
        for (int i = 0; i < Math.min(contractors.size(), 9); i++) {
            PlayerStats s = contractors.get(i);
            ItemStack icon = new ItemBuilder(Material.DIAMOND)
                    .name("&b#" + (i + 1) + " " + s.getName())
                    .lore("&7Spent: &a" + String.format("%.2f", s.getTotalSpent()),
                            "&7Contracts Posted: &e" + s.getContractsPosted())
                    .build();
            inv.setItem(10 + i, icon);
        }

        // Display top laborers in right column (slots 28-36)
        List<PlayerStats> laborers = plugin.getLeaderboardManager().getTopLaborers();
        for (int i = 0; i < Math.min(laborers.size(), 9); i++) {
            PlayerStats s = laborers.get(i);
            ItemStack icon = new ItemBuilder(Material.GOLD_INGOT)
                    .name("&6#" + (i + 1) + " " + s.getName())
                    .lore("&7Earned: &a" + String.format("%.2f", s.getTotalEarned()),
                            "&7Contracts Completed: &e" + s.getContractsCompleted())
                    .build();
            inv.setItem(28 + i, icon);
        }

        // Column headers
        inv.setItem(1, new ItemBuilder(Material.DIAMOND).name("&b&lTop Contractors").build());
        inv.setItem(19, new ItemBuilder(Material.GOLD_INGOT).name("&6&lTop Laborers").build());

        setState(player.getUniqueId(), GUIState.LEADERBOARD);
        player.openInventory(inv);
    }

    // ---- Mail GUI ----

    public void openMailGUI(Player player) {
        plugin.getMailManager().getMailEntries(player, entries -> {
            ConfigurationSection cfg = plugin.getConfigManager().getGuiConfig().getConfigurationSection("mail-gui");
            int rows = cfg.getInt("rows", 6);
            String title = colorize(cfg.getString("title", "Collection Mail"));

            Inventory inv = Bukkit.createInventory(new CBInventoryHolder("MAIL"), rows * 9, title);
            fillBackground(inv, cfg.getConfigurationSection("filler"));

            setGuiButton(inv, cfg, "buttons.back");
            if (!entries.isEmpty()) setGuiButton(inv, cfg, "buttons.collect-all");

            for (int i = 0; i < Math.min(entries.size(), 45); i++) {
                MailEntry entry = entries.get(i);
                ItemStack icon = new ItemBuilder(Material.PAPER)
                        .name("&e" + entry.getDescription())
                        .lore("&7Amount: &a" + String.format("%.2f", entry.getAmount()))
                        .build();
                inv.setItem(i, icon);
            }

            setState(player.getUniqueId(), GUIState.MAIL);
            player.openInventory(inv);
        });
    }

    // ---- Helpers ----

    private ItemStack buildContractIcon(Contract c) {
        Material mat = switch (c.getType()) {
            case BOUNTY_HUNT -> Material.PLAYER_HEAD;
            case ITEM_GATHERING -> Material.CHEST;
            case XP_SERVICE -> Material.EXPERIENCE_BOTTLE;
        };

        String statusColor = switch (c.getStatus()) {
            case OPEN -> "&a";
            case ACCEPTED -> "&e";
            case PAUSED -> "&6";
            default -> "&7";
        };

        String timeLeft = formatTimeLeft(c.getExpiresAt() - System.currentTimeMillis());

        return new ItemBuilder(mat)
                .name("&f" + formatType(c.getType()) + " &7#" + c.getId())
                .lore(
                        "&7Contractor: &e" + c.getContractorName(),
                        "&7Reward: &a" + String.format("%.2f", c.getReward()),
                        "&7Status: " + statusColor + c.getStatus().name(),
                        "&7Expires in: &7" + timeLeft,
                        "",
                        "&eClick for details."
                )
                .build();
    }

    private void setGuiButton(Inventory inv, ConfigurationSection section, String path) {
        ConfigurationSection btn = section.getConfigurationSection(path);
        if (btn == null) return;

        int slot = btn.getInt("slot", -1);
        if (slot < 0 || slot >= inv.getSize()) return;

        String matStr = btn.getString("item", "STONE");
        Material mat;
        try {
            mat = Material.valueOf(matStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            mat = Material.STONE;
        }

        String name = colorize(btn.getString("name", " "));
        List<String> lore = btn.getStringList("lore").stream()
                .map(this::colorize).collect(Collectors.toList());

        inv.setItem(slot, new ItemBuilder(mat).name(name).lore(lore).build());
    }

    private void fillBackground(Inventory inv, ConfigurationSection cfg) {
        if (cfg == null || !cfg.getBoolean("enabled", true)) return;
        String matStr = cfg.getString("item", "GRAY_STAINED_GLASS_PANE");
        String name = colorize(cfg.getString("name", " "));
        Material mat;
        try {
            mat = Material.valueOf(matStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            mat = Material.GRAY_STAINED_GLASS_PANE;
        }
        ItemStack filler = new ItemBuilder(mat).name(name).build();
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }
    }

    private String colorize(String s) {
        return plugin.getConfigManager().colorize(s);
    }

    private String formatType(Contract.ContractType type) {
        return switch (type) {
            case BOUNTY_HUNT -> "Bounty Hunt";
            case ITEM_GATHERING -> "Item Gathering";
            case XP_SERVICE -> "XP Service";
        };
    }

    private String formatTimeLeft(long millis) {
        if (millis <= 0) return "Expired";
        long seconds = millis / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        return hours + "h " + minutes + "m";
    }

    // ---- Inventory Holder ----

    /**
     * Custom InventoryHolder so we can identify our inventories in the listener.
     */
    public static class CBInventoryHolder implements InventoryHolder {
        private final String id;
        private Inventory inventory;

        public CBInventoryHolder(String id) { this.id = id; }

        @Override public Inventory getInventory() { return inventory; }
        public void setInventory(Inventory inv) { this.inventory = inv; }
        public String getId() { return id; }
    }
}
