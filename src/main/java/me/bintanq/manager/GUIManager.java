package me.bintanq.manager;

import me.bintanq.ContractBoard;
import me.bintanq.gui.GUIState;
import me.bintanq.model.Contract;
import me.bintanq.model.MailEntry;
import me.bintanq.model.PlayerStats;
import me.bintanq.util.ItemBuilder;
import me.bintanq.util.MetadataUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds and manages all plugin inventories.
 *
 * COMPLETE REWRITE with:
 * - Player stats GUI
 * - My contracts GUI (ongoing contracts)
 * - Player heads for bounties
 * - Actual items for item gathering
 * - Improved leaderboard
 * - All messages from messages.yml
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

    // ---- Main Board GUI ----

    public void openMainBoard(Player player) {
        ConfigurationSection cfg = plugin.getConfigManager().getGuiConfig().getConfigurationSection("main-board");
        int rows = cfg.getInt("rows", 6);
        String title = msg("title", cfg.getString("title", "&8Contract Board"));

        Inventory inv = Bukkit.createInventory(new CBInventoryHolder("MAIN_BOARD"), rows * 9, title);
        fillBackground(inv, cfg.getConfigurationSection("filler"));

        // Get player stats for display
        getPlayerStatsSync(player, stats -> {
            // Player stats button (player head)
            setPlayerStatsButton(inv, cfg, player, stats);

            // Contract type buttons
            setGuiButton(inv, cfg, "buttons.bounty-hunt");
            setGuiButton(inv, cfg, "buttons.item-gathering");
            setGuiButton(inv, cfg, "buttons.xp-services");

            // My contracts button
            setMyContractsButton(inv, cfg, player);

            // Other buttons
            setGuiButton(inv, cfg, "buttons.leaderboard");
            setMailButton(inv, cfg, player);
            setGuiButton(inv, cfg, "buttons.close");

            setState(player.getUniqueId(), GUIState.MAIN_BOARD);
            player.openInventory(inv);
        });
    }

    private void setPlayerStatsButton(Inventory inv, ConfigurationSection cfg, Player player, PlayerStats stats) {
        ConfigurationSection btn = cfg.getConfigurationSection("buttons.player-stats");
        if (btn == null) return;

        int slot = btn.getInt("slot", 4);
        ItemStack head = createPlayerHead(player.getName());

        String name = msg("name", btn.getString("name", "&e{player}'s Stats"))
                .replace("{player}", player.getName());

        List<String> lore = btn.getStringList("lore").stream()
                .map(this::msg)
                .map(l -> l.replace("{player}", player.getName()))
                .map(l -> l.replace("{posted}", String.valueOf(stats.getContractsPosted())))
                .map(l -> l.replace("{completed}", String.valueOf(stats.getContractsCompleted())))
                .map(l -> l.replace("{spent}", String.format("%.2f", stats.getTotalSpent())))
                .map(l -> l.replace("{earned}", String.format("%.2f", stats.getTotalEarned())))
                .collect(Collectors.toList());

        ItemStack item = new ItemBuilder(head)
                .name(name)
                .lore(lore)
                .build();

        inv.setItem(slot, item);
    }

    private void setMyContractsButton(Inventory inv, ConfigurationSection cfg, Player player) {
        ConfigurationSection btn = cfg.getConfigurationSection("buttons.my-contracts");
        if (btn == null) return;

        int slot = btn.getInt("slot", 48);
        Material mat = getMaterial(btn.getString("item", "WRITABLE_BOOK"));

        // Count active contracts
        int asContractor = (int) plugin.getContractManager().getActiveContracts().values().stream()
                .filter(c -> c.getContractorUUID().equals(player.getUniqueId()) && c.isActive())
                .count();

        int asWorker = (int) plugin.getContractManager().getActiveContracts().values().stream()
                .filter(c -> player.getUniqueId().equals(c.getWorkerUUID()))
                .count();

        String name = msg("name", btn.getString("name", "&d&lMy Contracts"));
        List<String> lore = btn.getStringList("lore").stream()
                .map(this::msg)
                .map(l -> l.replace("{posted}", String.valueOf(asContractor)))
                .map(l -> l.replace("{working}", String.valueOf(asWorker)))
                .collect(Collectors.toList());

        ItemStack item = new ItemBuilder(mat)
                .name(name)
                .lore(lore)
                .build();

        inv.setItem(slot, item);
    }

    private void setMailButton(Inventory inv, ConfigurationSection cfg, Player player) {
        ConfigurationSection btn = cfg.getConfigurationSection("buttons.mail");
        if (btn == null) return;

        int slot = btn.getInt("slot", 45);
        Material mat = getMaterial(btn.getString("item", "ENDER_CHEST"));

        // Get mail count
        plugin.getMailManager().getMailEntries(player, entries -> {
            String name = msg("name", btn.getString("name", "&d&lCollection Mail"));
            List<String> lore = btn.getStringList("lore").stream()
                    .map(this::msg)
                    .map(l -> l.replace("{mail_count}", String.valueOf(entries.size())))
                    .collect(Collectors.toList());

            ItemStack item = new ItemBuilder(mat)
                    .name(name)
                    .lore(lore)
                    .build();

            inv.setItem(slot, item);
        });
    }

    // ---- Contract List GUI ----

    public void openContractList(Player player, Contract.ContractType type, int page) {
        ConfigurationSection cfg = plugin.getConfigManager().getGuiConfig().getConfigurationSection("contract-list");
        int rows = cfg.getInt("rows", 6);

        String typeName = formatType(type);
        String title = msg("title", cfg.getString("title", "&8Contracts"))
                .replace("{type}", typeName);

        Inventory inv = Bukkit.createInventory(
                new CBInventoryHolder("CONTRACT_LIST_" + type.name()), rows * 9, title);
        fillBackground(inv, cfg.getConfigurationSection("filler"));

        List<Contract> contracts = plugin.getContractManager().getOpenContracts(type);
        int pageSize = 45;
        int start = page * pageSize;
        int end = Math.min(start + pageSize, contracts.size());

        for (int i = start; i < end; i++) {
            Contract c = contracts.get(i);
            inv.setItem(i - start, buildContractIcon(c, false));
        }

        // Navigation buttons
        setGuiButton(inv, cfg, "navigation.back");
        setGuiButton(inv, cfg, "navigation.new-contract");
        if (page > 0) {
            setNavigationButton(inv, cfg, "navigation.prev-page", page);
        }
        if (end < contracts.size()) {
            setNavigationButton(inv, cfg, "navigation.next-page", page + 2);
        }

        setState(player.getUniqueId(), GUIState.CONTRACT_LIST);
        player.openInventory(inv);
    }

    private void setNavigationButton(Inventory inv, ConfigurationSection cfg, String path, int pageNum) {
        ConfigurationSection btn = cfg.getConfigurationSection(path);
        if (btn == null) return;

        int slot = btn.getInt("slot", 45);
        Material mat = getMaterial(btn.getString("item", "ARROW"));
        String name = msg("name", btn.getString("name", "&ePage"));

        List<String> lore = btn.getStringList("lore").stream()
                .map(this::msg)
                .map(l -> l.replace("{page}", String.valueOf(pageNum)))
                .collect(Collectors.toList());

        ItemStack item = new ItemBuilder(mat)
                .name(name)
                .lore(lore)
                .build();

        inv.setItem(slot, item);
    }

    // ---- Contract Detail GUI ----

    public void openContractDetail(Player player, Contract contract) {
        ConfigurationSection cfg = plugin.getConfigManager().getGuiConfig().getConfigurationSection("contract-detail");
        int rows = cfg.getInt("rows", 5);

        String title = msg("title", cfg.getString("title", "&8Contract #{id}"))
                .replace("{id}", String.valueOf(contract.getId()));

        Inventory inv = Bukkit.createInventory(
                new CBInventoryHolder("CONTRACT_DETAIL_" + contract.getId()), rows * 9, title);
        fillBackground(inv, cfg.getConfigurationSection("filler"));

        // Center: Contract info with proper item/head
        int infoSlot = cfg.getInt("info-slot", 13);
        inv.setItem(infoSlot, buildContractIcon(contract, true));

        boolean isContractor = player.getUniqueId().equals(contract.getContractorUUID());
        boolean isWorker = player.getUniqueId().equals(contract.getWorkerUUID());
        boolean isOpen = contract.getStatus() == Contract.ContractStatus.OPEN;
        boolean isAccepted = contract.getStatus() == Contract.ContractStatus.ACCEPTED;

        // Show appropriate buttons based on role
        if (!isContractor && isOpen) {
            setGuiButton(inv, cfg, "buttons.accept");
        }
        if (isContractor && (isOpen || isAccepted)) {
            setGuiButton(inv, cfg, "buttons.cancel");
        }
        if (isWorker && isAccepted) {
            setGuiButton(inv, cfg, "buttons.submit");
        }
        setGuiButton(inv, cfg, "buttons.back");

        setState(player.getUniqueId(), GUIState.CONTRACT_DETAIL);
        player.openInventory(inv);
    }

    // ---- Player Stats GUI (NEW) ----

    public void openPlayerStats(Player player) {
        ConfigurationSection cfg = plugin.getConfigManager().getGuiConfig().getConfigurationSection("player-stats");
        int rows = cfg.getInt("rows", 4);

        String title = msg("title", cfg.getString("title", "&8{player}'s Statistics"))
                .replace("{player}", player.getName());

        Inventory inv = Bukkit.createInventory(new CBInventoryHolder("PLAYER_STATS"), rows * 9, title);
        fillBackground(inv, cfg.getConfigurationSection("filler"));

        getPlayerStatsSync(player, stats -> {
            // Profile head
            setProfileButton(inv, cfg, player, stats);

            // Active as contractor
            setActiveContractorButton(inv, cfg, player);

            // Active as worker
            setActiveWorkerButton(inv, cfg, player);

            // Back button
            setGuiButton(inv, cfg, "buttons.back");

            setState(player.getUniqueId(), GUIState.MAIN_BOARD); // Keep main board state
            player.openInventory(inv);
        });
    }

    private void setProfileButton(Inventory inv, ConfigurationSection cfg, Player player, PlayerStats stats) {
        ConfigurationSection btn = cfg.getConfigurationSection("buttons.profile");
        if (btn == null) return;

        int slot = btn.getInt("slot", 4);
        ItemStack head = createPlayerHead(player.getName());

        String name = msg("name", btn.getString("name", "&e&l{player}"))
                .replace("{player}", player.getName());

        List<String> lore = btn.getStringList("lore").stream()
                .map(this::msg)
                .map(l -> l.replace("{player}", player.getName()))
                .map(l -> l.replace("{joined}", "Unknown")) // Can add join date if tracked
                .map(l -> l.replace("{posted}", String.valueOf(stats.getContractsPosted())))
                .map(l -> l.replace("{completed}", String.valueOf(stats.getContractsCompleted())))
                .map(l -> l.replace("{spent}", String.format("%.2f", stats.getTotalSpent())))
                .map(l -> l.replace("{earned}", String.format("%.2f", stats.getTotalEarned())))
                .collect(Collectors.toList());

        ItemStack item = new ItemBuilder(head)
                .name(name)
                .lore(lore)
                .build();

        inv.setItem(slot, item);
    }

    private void setActiveContractorButton(Inventory inv, ConfigurationSection cfg, Player player) {
        ConfigurationSection btn = cfg.getConfigurationSection("buttons.active-contractor");
        if (btn == null) return;

        int slot = btn.getInt("slot", 20);
        Material mat = getMaterial(btn.getString("item", "DIAMOND"));

        List<Contract> active = plugin.getContractManager().getActiveContracts().values().stream()
                .filter(c -> c.getContractorUUID().equals(player.getUniqueId()) && c.isActive())
                .collect(Collectors.toList());

        int pending = (int) active.stream()
                .filter(c -> c.getStatus() == Contract.ContractStatus.ACCEPTED)
                .count();

        String name = msg("name", btn.getString("name", "&b&lAs Contractor"));
        List<String> lore = btn.getStringList("lore").stream()
                .map(this::msg)
                .map(l -> l.replace("{active_posted}", String.valueOf(active.size())))
                .map(l -> l.replace("{pending}", String.valueOf(pending)))
                .collect(Collectors.toList());

        inv.setItem(slot, new ItemBuilder(mat).name(name).lore(lore).build());
    }

    private void setActiveWorkerButton(Inventory inv, ConfigurationSection cfg, Player player) {
        ConfigurationSection btn = cfg.getConfigurationSection("buttons.active-worker");
        if (btn == null) return;

        int slot = btn.getInt("slot", 24);
        Material mat = getMaterial(btn.getString("item", "IRON_PICKAXE"));

        List<Contract> working = plugin.getContractManager().getActiveContracts().values().stream()
                .filter(c -> player.getUniqueId().equals(c.getWorkerUUID()))
                .collect(Collectors.toList());

        // Ready to submit (item gathering that's submitted)
        int ready = (int) working.stream()
                .filter(c -> c.getType() == Contract.ContractType.ITEM_GATHERING)
                .filter(c -> MetadataUtil.isItemSubmitted(c.getMetadata()))
                .count();

        String name = msg("name", btn.getString("name", "&a&lAs Worker"));
        List<String> lore = btn.getStringList("lore").stream()
                .map(this::msg)
                .map(l -> l.replace("{in_progress}", String.valueOf(working.size())))
                .map(l -> l.replace("{ready}", String.valueOf(ready)))
                .collect(Collectors.toList());

        inv.setItem(slot, new ItemBuilder(mat).name(name).lore(lore).build());
    }

    // ---- My Contracts GUI (NEW) ----

    public void openMyContracts(Player player) {
        ConfigurationSection cfg = plugin.getConfigManager().getGuiConfig().getConfigurationSection("my-contracts");
        int rows = cfg.getInt("rows", 6);
        String title = msg("title", cfg.getString("title", "&8My Contracts"));

        Inventory inv = Bukkit.createInventory(new CBInventoryHolder("MY_CONTRACTS"), rows * 9, title);
        fillBackground(inv, cfg.getConfigurationSection("filler"));

        // Tab buttons at top
        setMyContractsTab(inv, cfg, player, "tabs.as-contractor", true);
        setMyContractsTab(inv, cfg, player, "tabs.as-worker", false);

        // Show contracts as contractor (default view)
        List<Contract> myContracts = plugin.getContractManager().getActiveContracts().values().stream()
                .filter(c -> c.getContractorUUID().equals(player.getUniqueId()) && c.isActive())
                .sorted(Comparator.comparingLong(Contract::getCreatedAt).reversed())
                .limit(45)
                .collect(Collectors.toList());

        for (int i = 0; i < myContracts.size() && i < 45; i++) {
            inv.setItem(9 + i, buildContractIcon(myContracts.get(i), false));
        }

        // Back button
        setGuiButton(inv, cfg, "navigation.back");

        setState(player.getUniqueId(), GUIState.MAIN_BOARD);
        player.openInventory(inv);
    }

    private void setMyContractsTab(Inventory inv, ConfigurationSection cfg, Player player, String path, boolean asContractor) {
        ConfigurationSection btn = cfg.getConfigurationSection(path);
        if (btn == null) return;

        int slot = btn.getInt("slot", 2);
        Material mat = getMaterial(btn.getString("item", "DIAMOND"));

        int count;
        if (asContractor) {
            count = (int) plugin.getContractManager().getActiveContracts().values().stream()
                    .filter(c -> c.getContractorUUID().equals(player.getUniqueId()) && c.isActive())
                    .count();
        } else {
            count = (int) plugin.getContractManager().getActiveContracts().values().stream()
                    .filter(c -> player.getUniqueId().equals(c.getWorkerUUID()))
                    .count();
        }

        String name = msg("name", btn.getString("name", "&bTab"));
        List<String> lore = btn.getStringList("lore").stream()
                .map(this::msg)
                .map(l -> l.replace("{count}", String.valueOf(count)))
                .collect(Collectors.toList());

        inv.setItem(slot, new ItemBuilder(mat).name(name).lore(lore).build());
    }

    // ---- Leaderboard GUI (IMPROVED) ----

    public void openLeaderboard(Player player) {
        ConfigurationSection cfg = plugin.getConfigManager().getGuiConfig().getConfigurationSection("leaderboard");
        int rows = cfg.getInt("rows", 6);
        String title = msg("title", cfg.getString("title", "&8Leaderboards"));

        Inventory inv = Bukkit.createInventory(new CBInventoryHolder("LEADERBOARD"), rows * 9, title);
        fillBackground(inv, cfg.getConfigurationSection("filler"));

        // Header items
        setLeaderboardHeader(inv, cfg, "headers.contractors", 1);
        setLeaderboardHeader(inv, cfg, "headers.laborers", 7);

        // Top contractors (left side: slots 10-16, 19-25)
        List<PlayerStats> contractors = plugin.getLeaderboardManager().getTopContractors();
        int[] contractorSlots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21};
        for (int i = 0; i < Math.min(contractors.size(), contractorSlots.length); i++) {
            PlayerStats s = contractors.get(i);
            ItemStack icon = createLeaderboardEntry(s, i + 1, true);
            inv.setItem(contractorSlots[i], icon);
        }

        // Top laborers (right side: slots 28-34, 37-43)
        List<PlayerStats> laborers = plugin.getLeaderboardManager().getTopLaborers();
        int[] laborerSlots = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39};
        for (int i = 0; i < Math.min(laborers.size(), laborerSlots.length); i++) {
            PlayerStats s = laborers.get(i);
            ItemStack icon = createLeaderboardEntry(s, i + 1, false);
            inv.setItem(laborerSlots[i], icon);
        }

        // Back button
        setGuiButton(inv, cfg, "buttons.back");

        setState(player.getUniqueId(), GUIState.LEADERBOARD);
        player.openInventory(inv);
    }

    private void setLeaderboardHeader(Inventory inv, ConfigurationSection cfg, String path, int slot) {
        ConfigurationSection btn = cfg.getConfigurationSection(path);
        if (btn == null) return;

        Material mat = getMaterial(btn.getString("item", "DIAMOND"));
        String name = msg("name", btn.getString("name", "&bHeader"));
        List<String> lore = btn.getStringList("lore").stream()
                .map(this::msg)
                .collect(Collectors.toList());

        inv.setItem(slot, new ItemBuilder(mat).name(name).lore(lore).build());
    }

    private ItemStack createLeaderboardEntry(PlayerStats stats, int rank, boolean isContractor) {
        Material mat = isContractor ? Material.DIAMOND : Material.GOLD_INGOT;
        String rankEmoji = getRankEmoji(rank);

        double amount = isContractor ? stats.getTotalSpent() : stats.getTotalEarned();
        String amountType = isContractor ? "Spent" : "Earned";
        int contracts = isContractor ? stats.getContractsPosted() : stats.getContractsCompleted();

        return new ItemBuilder(mat)
                .name(rankEmoji + " &e" + stats.getName())
                .lore(
                        "&7Rank: &e#" + rank,
                        "&7" + amountType + ": &a$" + String.format("%.2f", amount),
                        "&7Contracts: &e" + contracts
                )
                .build();
    }

    private String getRankEmoji(int rank) {
        return switch (rank) {
            case 1 -> "&6&l🥇";
            case 2 -> "&7&l🥈";
            case 3 -> "&c&l🥉";
            default -> "&e#" + rank;
        };
    }

    // ---- Mail GUI ----

    public void openMailGUI(Player player) {
        plugin.getMailManager().getMailEntries(player, entries -> {
            ConfigurationSection cfg = plugin.getConfigManager().getGuiConfig().getConfigurationSection("mail-gui");
            int rows = cfg.getInt("rows", 6);
            String title = msg("title", cfg.getString("title", "&8Collection Mail"));

            Inventory inv = Bukkit.createInventory(new CBInventoryHolder("MAIL"), rows * 9, title);
            fillBackground(inv, cfg.getConfigurationSection("filler"));

            // Calculate total
            double total = entries.stream().mapToDouble(MailEntry::getAmount).sum();

            // Set collect all button with total
            setCollectAllButton(inv, cfg, total, entries.isEmpty());
            setGuiButton(inv, cfg, "buttons.back");

            // Display mail entries
            for (int i = 0; i < Math.min(entries.size(), 45); i++) {
                MailEntry entry = entries.get(i);
                ItemStack icon = new ItemBuilder(Material.PAPER)
                        .name("&e" + entry.getDescription())
                        .lore("&7Amount: &a$" + String.format("%.2f", entry.getAmount()))
                        .build();
                inv.setItem(i, icon);
            }

            setState(player.getUniqueId(), GUIState.MAIL);
            player.openInventory(inv);
        });
    }

    private void setCollectAllButton(Inventory inv, ConfigurationSection cfg, double total, boolean empty) {
        ConfigurationSection btn = cfg.getConfigurationSection("buttons.collect-all");
        if (btn == null) return;

        int slot = btn.getInt("slot", 49);
        Material mat = empty ? Material.BARRIER : getMaterial(btn.getString("item", "HOPPER"));

        String name = msg("name", btn.getString("name", "&a&lCollect All"));
        List<String> lore = btn.getStringList("lore").stream()
                .map(this::msg)
                .map(l -> l.replace("{total}", String.format("%.2f", total)))
                .collect(Collectors.toList());

        if (empty) {
            name = "&7&lNo Mail";
            lore = Arrays.asList("&7Your mailbox is empty");
        }

        inv.setItem(slot, new ItemBuilder(mat).name(name).lore(lore).build());
    }

    // ---- Contract Icon Building ----

    /**
     * Builds a contract display item with proper icon based on type
     * @param detailed If true, shows full details for contract detail GUI
     */
    private ItemStack buildContractIcon(Contract contract, boolean detailed) {
        ConfigurationSection templates = plugin.getConfigManager().getGuiConfig()
                .getConfigurationSection("display-templates");

        String templatePath = switch (contract.getType()) {
            case BOUNTY_HUNT -> "bounty";
            case ITEM_GATHERING -> "item-gathering";
            case XP_SERVICE -> "xp-services";
        };

        ConfigurationSection template = templates.getConfigurationSection(templatePath);
        if (template == null) {
            return new ItemStack(Material.PAPER); // Fallback
        }

        // Get base item
        ItemStack item = getContractItem(contract, template);

        // Get name
        String name = msg("name", template.getString("name", "&fContract"))
                .replace("{id}", String.valueOf(contract.getId()));

        // Build lore
        List<String> lore = buildContractLore(contract, template, detailed);

        return new ItemBuilder(item)
                .name(name)
                .lore(lore)
                .build();
    }

    private ItemStack getContractItem(Contract contract, ConfigurationSection template) {
        switch (contract.getType()) {
            case BOUNTY_HUNT -> {
                // Use target's player head
                String targetName = MetadataUtil.getBountyTargetName(contract.getMetadata());
                return createPlayerHead(targetName);
            }
            case ITEM_GATHERING -> {
                // Use actual item material
                String materialName = MetadataUtil.getItemMaterial(contract.getMetadata());
                try {
                    Material mat = Material.valueOf(materialName);
                    return new ItemStack(mat);
                } catch (IllegalArgumentException e) {
                    return new ItemStack(Material.CHEST);
                }
            }
            case XP_SERVICE -> {
                return new ItemStack(Material.EXPERIENCE_BOTTLE);
            }
        }
        return new ItemStack(Material.PAPER);
    }

    private List<String> buildContractLore(Contract contract, ConfigurationSection template, boolean detailed) {
        List<String> baseLore = template.getStringList("lore").stream()
                .map(this::msg)
                .collect(Collectors.toList());

        // Replace common placeholders
        return baseLore.stream()
                .map(l -> l.replace("{contractor}", contract.getContractorName()))
                .map(l -> l.replace("{reward}", String.format("%.2f", contract.getReward())))
                .map(l -> l.replace("{status}", getStatusDisplay(contract.getStatus())))
                .map(l -> l.replace("{expires}", formatTimeLeft(contract.getExpiresAt() - System.currentTimeMillis())))
                .map(l -> replaceTypeSpecific(l, contract))
                .map(l -> l.replace("{action}", getActionText(contract, detailed)))
                .collect(Collectors.toList());
    }

    private String replaceTypeSpecific(String line, Contract contract) {
        switch (contract.getType()) {
            case BOUNTY_HUNT -> {
                String target = MetadataUtil.getBountyTargetName(contract.getMetadata());
                boolean anon = MetadataUtil.isBountyAnonymous(contract.getMetadata());
                return line.replace("{target}", target)
                        .replace("{anonymous}", anon ? "Yes" : "No");
            }
            case ITEM_GATHERING -> {
                String material = MetadataUtil.getItemMaterial(contract.getMetadata());
                int amount = MetadataUtil.getItemAmount(contract.getMetadata());
                return line.replace("{material}", formatMaterial(material))
                        .replace("{amount}", String.valueOf(amount));
            }
            case XP_SERVICE -> {
                int points = MetadataUtil.getXPPoints(contract.getMetadata());
                String mode = MetadataUtil.getXPMode(contract.getMetadata());
                return line.replace("{points}", String.valueOf(points))
                        .replace("{mode}", mode.replace("_", " "));
            }
        }
        return line;
    }

    private String getActionText(Contract contract, boolean detailed) {
        if (!detailed) {
            return msg("gui.click-details");
        }

        switch (contract.getStatus()) {
            case OPEN -> {
                return msg("gui.click-accept");
            }
            case ACCEPTED -> {
                return msg("gui.click-submit");
            }
            default -> {
                return "";
            }
        }
    }

    private String getStatusDisplay(Contract.ContractStatus status) {
        return msg("gui.status." + status.name().toLowerCase());
    }

    // ---- Helper Methods ----

    private ItemStack createPlayerHead(String playerName) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);
        meta.setOwningPlayer(offlinePlayer);

        head.setItemMeta(meta);
        return head;
    }

    private void setGuiButton(Inventory inv, ConfigurationSection section, String path) {
        ConfigurationSection btn = section.getConfigurationSection(path);
        if (btn == null) return;

        int slot = btn.getInt("slot", -1);
        if (slot < 0 || slot >= inv.getSize()) return;

        Material mat = getMaterial(btn.getString("item", "STONE"));
        String name = msg("name", btn.getString("name", " "));
        List<String> lore = btn.getStringList("lore").stream()
                .map(this::msg)
                .collect(Collectors.toList());

        inv.setItem(slot, new ItemBuilder(mat).name(name).lore(lore).build());
    }

    private void fillBackground(Inventory inv, ConfigurationSection cfg) {
        if (cfg == null || !cfg.getBoolean("enabled", true)) return;

        Material mat = getMaterial(cfg.getString("item", "GRAY_STAINED_GLASS_PANE"));
        String name = msg("name", cfg.getString("name", " "));

        ItemStack filler = new ItemBuilder(mat).name(name).build();
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }
    }

    private Material getMaterial(String materialName) {
        try {
            return Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Material.STONE;
        }
    }

    private String msg(String path) {
        return plugin.getConfigManager().getMessage(path);
    }

    private String msg(String key, String fallback) {
        String message = plugin.getConfigManager().getRawMessage(key);
        if (message.contains("Missing")) {
            return plugin.getConfigManager().colorize(fallback);
        }
        return message;
    }

    private String formatType(Contract.ContractType type) {
        return switch (type) {
            case BOUNTY_HUNT -> "Bounty Hunt";
            case ITEM_GATHERING -> "Item Gathering";
            case XP_SERVICE -> "XP Service";
        };
    }

    private String formatMaterial(String material) {
        try {
            String[] words = material.toLowerCase().split("_");
            StringBuilder result = new StringBuilder();
            for (String word : words) {
                if (result.length() > 0) result.append(" ");
                result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            return result.toString();
        } catch (Exception e) {
            return material;
        }
    }

    private String formatTimeLeft(long millis) {
        if (millis <= 0) return msg("time.expired");

        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return msg("time.days")
                    .replace("{days}", String.valueOf(days))
                    .replace("{hours}", String.valueOf(hours % 24));
        } else if (hours > 0) {
            return msg("time.hours")
                    .replace("{hours}", String.valueOf(hours))
                    .replace("{minutes}", String.valueOf(minutes % 60));
        } else if (minutes > 0) {
            return msg("time.minutes")
                    .replace("{minutes}", String.valueOf(minutes));
        } else {
            return msg("time.seconds")
                    .replace("{seconds}", String.valueOf(seconds));
        }
    }

    private void getPlayerStatsSync(Player player, java.util.function.Consumer<PlayerStats> callback) {
        plugin.getLeaderboardManager().getPlayerStats(player.getUniqueId(), stats -> {
            if (stats == null) {
                stats = new PlayerStats(player.getUniqueId(), player.getName(), 0, 0, 0, 0);
            }
            callback.accept(stats);
        });
    }

    // ---- Inventory Holder ----

    public static class CBInventoryHolder implements InventoryHolder {
        private final String id;
        private Inventory inventory;

        public CBInventoryHolder(String id) { this.id = id; }

        @Override public Inventory getInventory() { return inventory; }
        public void setInventory(Inventory inv) { this.inventory = inv; }
        public String getId() { return id; }
    }
}