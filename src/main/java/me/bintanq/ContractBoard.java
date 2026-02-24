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

/**
 * ContractBoard — Main plugin entrypoint.
 * Initializes all managers, listeners, commands, and optional integrations.
 */
public class ContractBoard extends JavaPlugin {

    private static ContractBoard instance;

    private DatabaseManager databaseManager;
    private ConfigManager configManager;
    private ContractManager contractManager;
    private BountyManager bountyManager;
    private ItemGatheringManager itemGatheringManager;
    private XPServiceManager xpServiceManager;
    private MailManager mailManager;
    private LeaderboardManager leaderboardManager;
    private GUIManager guiManager;

    private Economy economy;

    @Override
    public void onEnable() {
        instance = this;

        // Save default resource files
        saveDefaultConfig();
        saveResource("messages.yml", false);
        saveResource("gui.yml", false);

        // Configs first — everything else depends on them
        this.configManager = new ConfigManager(this);

        // Database — synchronous connection on enable
        this.databaseManager = new DatabaseManager(this);
        if (!databaseManager.connect()) {
            getLogger().severe("SQLite connection failed! Disabling ContractBoard.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Vault economy (soft dependency)
        if (!setupEconomy()) {
            getLogger().warning("Vault economy not found. Economy features disabled.");
        }

        // Managers (order matters: leaderboard & mail before contractManager)
        this.mailManager = new MailManager(this);
        this.leaderboardManager = new LeaderboardManager(this);
        this.contractManager = new ContractManager(this);
        this.bountyManager = new BountyManager(this);
        this.itemGatheringManager = new ItemGatheringManager(this);
        this.xpServiceManager = new XPServiceManager(this);
        this.guiManager = new GUIManager(this);

        // Listeners
        getServer().getPluginManager().registerEvents(new GUIListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // Commands
        ContractCommand cmd = new ContractCommand(this);
        getCommand("contract").setExecutor(cmd);
        getCommand("contract").setTabCompleter(cmd);

        // PlaceholderAPI expansion
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new ContractBoardExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered.");
        }

        // Start the expiration checker (runs async, every 30 seconds)
        contractManager.startExpirationTask();

        getLogger().info("ContractBoard v" + getDescription().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (bountyManager != null) bountyManager.cleanup();
        if (databaseManager != null) databaseManager.disconnect();
        getLogger().info("ContractBoard disabled.");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return true;
    }

    /** Reloads all config files and refreshes cached data. */
    public void reload() {
        reloadConfig();
        configManager.reload();
        leaderboardManager.reload();
        getLogger().info("ContractBoard config reloaded.");
    }

    // ---- Getters ----

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