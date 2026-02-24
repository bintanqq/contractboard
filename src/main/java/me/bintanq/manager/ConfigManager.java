package me.bintanq.manager;

import me.bintanq.ContractBoard;
import me.bintanq.model.Contract.ContractType;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.List;

/**
 * Centralizes access to all plugin configurations.
 * ADDED: Support for announcement settings
 */
public class ConfigManager {

    private final ContractBoard plugin;

    private FileConfiguration config;
    private FileConfiguration messages;
    private FileConfiguration gui;

    public ConfigManager(ContractBoard plugin) {
        this.plugin = plugin;
        load();
    }

    public void reload() { load(); }

    private void load() {
        plugin.reloadConfig();
        config = plugin.getConfig();
        messages = loadYml("messages.yml");
        gui = loadYml("gui.yml");
    }

    private FileConfiguration loadYml(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) plugin.saveResource(name, false);
        return YamlConfiguration.loadConfiguration(file);
    }

    // ---- Feature Toggles ----

    public boolean isBountyEnabled() { return config.getBoolean("features.bounty-hunt", true); }
    public boolean isItemGatheringEnabled() { return config.getBoolean("features.item-gathering", true); }
    public boolean isXPServiceEnabled() { return config.getBoolean("features.xp-services", true); }

    // ---- Announcement Settings ----

    public boolean isAnnouncementEnabled() {
        return config.getBoolean("announcements.enabled", true);
    }

    public boolean isAnnouncementEnabled(ContractType type) {
        String path = switch (type) {
            case BOUNTY_HUNT -> "announcements.bounty-hunt.enabled";
            case ITEM_GATHERING -> "announcements.item-gathering.enabled";
            case XP_SERVICE -> "announcements.xp-services.enabled";
        };
        return config.getBoolean(path, true);
    }

    public List<String> getAnnouncementMessages(ContractType type) {
        String path = switch (type) {
            case BOUNTY_HUNT -> "announcements.bounty-hunt.messages";
            case ITEM_GATHERING -> "announcements.item-gathering.messages";
            case XP_SERVICE -> "announcements.xp-services.messages";
        };
        return config.getStringList(path);
    }

    // ---- Tax Rates ----

    public double getTaxRate(ContractType type) {
        return switch (type) {
            case BOUNTY_HUNT -> config.getDouble("tax.bounty-hunt", 5.0);
            case ITEM_GATHERING -> config.getDouble("tax.item-gathering", 3.0);
            case XP_SERVICE -> config.getDouble("tax.xp-services", 4.0);
        };
    }

    // ---- Expiration: MINUTES → milliseconds ----

    public long getExpirationMillis(ContractType type) {
        long minutes = switch (type) {
            case BOUNTY_HUNT -> config.getLong("expiration.bounty-hunt", 4320);
            case ITEM_GATHERING -> config.getLong("expiration.item-gathering", 2880);
            case XP_SERVICE -> config.getLong("expiration.xp-services", 1440);
        };
        return minutes * 60_000L;
    }

    // ---- Price Limits ----

    public double getMinPrice(ContractType type) {
        return switch (type) {
            case BOUNTY_HUNT -> config.getDouble("price-limits.bounty-hunt.min", 100.0);
            case ITEM_GATHERING -> config.getDouble("price-limits.item-gathering.min", 10.0);
            case XP_SERVICE -> config.getDouble("price-limits.xp-services.min", 10.0);
        };
    }

    public double getMaxPrice(ContractType type) {
        return switch (type) {
            case BOUNTY_HUNT -> config.getDouble("price-limits.bounty-hunt.max", 1_000_000.0);
            case ITEM_GATHERING -> config.getDouble("price-limits.item-gathering.max", 500_000.0);
            case XP_SERVICE -> config.getDouble("price-limits.xp-services.max", 200_000.0);
        };
    }

    // ---- Contract Limits ----

    public int getContractLimit(Player player) {
        if (player.hasPermission("contractboard.max.unlimited")) {
            return Integer.MAX_VALUE;
        }

        int highest = -1;
        for (org.bukkit.permissions.PermissionAttachmentInfo pai : player.getEffectivePermissions()) {
            String perm = pai.getPermission().toLowerCase();
            if (!pai.getValue()) continue;
            if (!perm.startsWith("contractboard.max.")) continue;
            String suffix = perm.substring("contractboard.max.".length());
            try {
                int val = Integer.parseInt(suffix);
                if (val > highest) highest = val;
            } catch (NumberFormatException ignored) {}
        }

        if (highest >= 0) return highest;

        return config.getInt("contract-limits.default-max", 3);
    }

    // ---- Bounty ----

    public double getAnonymousExtraCost() { return config.getDouble("bounty.anonymous-extra-cost", 50.0); }
    public int getBossBarUpdateInterval() { return config.getInt("bounty.bossbar-update-interval", 5); }

    public BarColor getBossBarColor() {
        try {
            return BarColor.valueOf(config.getString("bounty.bossbar-color", "YELLOW").toUpperCase());
        } catch (IllegalArgumentException e) { return BarColor.YELLOW; }
    }

    public BarStyle getBossBarStyle() {
        try {
            return BarStyle.valueOf(config.getString("bounty.bossbar-style", "SOLID").toUpperCase());
        } catch (IllegalArgumentException e) { return BarStyle.SOLID; }
    }

    // ---- XP Service ----

    public String getSoulBottleItem() { return config.getString("xp-services.soul-bottle-item", "GLASS_BOTTLE"); }
    public String getSoulBottleName() { return config.getString("xp-services.soul-bottle-name", "&b&lSoul Bottle"); }
    public List<String> getSoulBottleLore() { return config.getStringList("xp-services.soul-bottle-lore"); }

    // ---- Database ----

    public String getDatabaseFile() { return config.getString("database.file", "contractboard.db"); }

    // ---- Messages ----

    public String getMessage(String path) {
        String msg = messages.getString(path, "&cMissing message: " + path);
        String prefix = messages.getString("prefix", "");
        return colorize(prefix + msg);
    }

    public String getRawMessage(String path) {
        return colorize(messages.getString(path, "&cMissing: " + path));
    }

    // ---- GUI ----

    public FileConfiguration getGuiConfig() { return gui; }
    public FileConfiguration getConfig() { return config; }

    // ---- Utility ----

    public String colorize(String s) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', s);
    }
}