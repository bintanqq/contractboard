package me.bintanq;

import me.bintanq.command.ContractCommand;
import me.bintanq.database.DatabaseManager;
import me.bintanq.listener.GUIListener;
import me.bintanq.listener.PlayerListener;
import me.bintanq.manager.*;
import me.bintanq.placeholder.ContractBoardExpansion;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * ContractBoard - Main plugin entrypoint.
 * Initializes all managers, listeners, commands, and optional integrations.
 */
public class ContractBoard extends JavaPlugin {

    // Singleton instance
    private static ContractBoard instance;

    // Core managers
    private DatabaseManager databaseManager;
    private ConfigManager configManager;
    private ContractManager contractManager;
    private BountyManager bountyManager;
    private ItemGatheringManager itemGatheringManager;
    private XPServiceManager xpServiceManager;
    private MailManager mailManager;
    private LeaderboardManager leaderboardManager;
    private GUIManager guiManager;

    // Vault economy
    private Economy economy;

    @Override
    public void onEnable() {
        instance = this;

        // Save default configs
        saveDefaultConfig();
        saveResource("messages.yml", false);
        saveResource("gui.yml", false);

        // Load configs
        this.configManager = new ConfigManager(this);

        // Connect database
        this.databaseManager = new DatabaseManager(this);
        if (!databaseManager.connect()) {
            getLogger().severe("Failed to connect to SQLite database! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Setup Vault Economy
        if (!setupEconomy()) {
            getLogger().warning("Vault economy not found. Economy features will be unavailable.");
        }

        // Initialize managers
        this.mailManager = new MailManager(this);
        this.leaderboardManager = new LeaderboardManager(this);
        this.contractManager = new ContractManager(this);
        this.bountyManager = new BountyManager(this);
        this.itemGatheringManager = new ItemGatheringManager(this);
        this.xpServiceManager = new XPServiceManager(this);
        this.guiManager = new GUIManager(this);

        // Register listeners
        getServer().getPluginManager().registerEvents(new GUIListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // Register commands
        ContractCommand contractCommand = new ContractCommand(this);
        getCommand("contract").setExecutor(contractCommand);
        getCommand("contract").setTabCompleter(contractCommand);

        // Register PlaceholderAPI expansion if present
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new ContractBoardExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered.");
        }

        // Start expiration task
        contractManager.startExpirationTask();

        getLogger().info("ContractBoard v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        // Cancel all boss bars
        if (bountyManager != null) bountyManager.cleanup();

        // Close DB connection
        if (databaseManager != null) databaseManager.disconnect();

        getLogger().info("ContractBoard disabled.");
    }

    /**
     * Hooks into Vault economy.
     */
    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return true;
    }

    /**
     * Reload all configurations and managers.
     */
    public void reload() {
        reloadConfig();
        configManager.reload();
        leaderboardManager.reload();
        getLogger().info("ContractBoard reloaded.");
    }

    // --- Getters ---

    public static ContractBoard getInstance() { return instance; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public ConfigManager getConfigManager() { return configManager; }
    public ContractManager getContractManager() { return contractManager; }
    public BountyManager getBountyManager() { return bountyManager; }
    public ItemGatheringManager getItemGatheringManager() { return itemGatheringManager; }
    public XPServiceManager getXPServiceManager() { return xpServiceManager; }
    public MailManager getMailManager() { return mailManager; }
    public LeaderboardManager getLeaderboardManager() { return leaderboardManager; }
    public GUIManager getGUIManager() { return guiManager; }
    public Economy getEconomy() { return economy; }
    public boolean hasEconomy() { return economy != null; }
}
