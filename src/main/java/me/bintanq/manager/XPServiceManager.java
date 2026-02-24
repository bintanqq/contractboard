package me.bintanq.manager;

import me.bintanq.ContractBoard;
import me.bintanq.model.Contract;
import me.bintanq.util.MetadataUtil;
import me.bintanq.util.XPUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

/**
 * Manages XP Service contracts.
 *
 * Modes:
 *  - INSTANT_DRAIN: Worker's existing levels are consumed on Submit.
 *  - ACTIVE_GRIND: Worker receives a Soul Bottle NBT item; XP gained from kills fills it.
 *
 * XP Math uses accurate Minecraft XP point formulas via {@link XPUtil}.
 */
public class XPServiceManager {

    private final ContractBoard plugin;

    // NBT key for Soul Bottle
    public static final String SOUL_BOTTLE_KEY = "contractboard_soul_bottle";
    public static final String SOUL_BOTTLE_CONTRACT_KEY = "contractboard_contract_id";

    // Track active grind sessions: workerUUID -> contractId
    private final java.util.Map<UUID, Integer> activeGrindSessions = new java.util.HashMap<>();
    // workerUUID -> current XP points collected
    private final java.util.Map<UUID, Integer> grindProgress = new java.util.HashMap<>();

    public XPServiceManager(ContractBoard plugin) {
        this.plugin = plugin;
    }

    /**
     * Posts an XP service contract.
     * Metadata: "xp_points|mode" where mode = INSTANT_DRAIN or ACTIVE_GRIND
     */
    public void postContract(Player contractor, int xpPoints, double reward, String mode) {
        String metadata = MetadataUtil.buildXPMeta(xpPoints, mode);

        plugin.getContractManager().createContract(
                contractor,
                Contract.ContractType.XP_SERVICE,
                reward,
                metadata,
                null
        );
    }

    /**
     * Worker submits work for an XP contract.
     * Routes to the correct mode.
     */
    public void submitXP(Player worker, Contract contract) {
        String mode = MetadataUtil.getXPMode(contract.getMetadata());
        int required = MetadataUtil.getXPPoints(contract.getMetadata());

        switch (mode) {
            case "INSTANT_DRAIN" -> handleInstantDrain(worker, contract, required);
            case "ACTIVE_GRIND" -> handleActiveGrindSubmit(worker, contract, required);
            default -> worker.sendMessage(plugin.getConfigManager().colorize("&cUnknown XP mode."));
        }
    }

    // ---- Instant Drain ----

    private void handleInstantDrain(Player worker, Contract contract, int requiredPoints) {
        int workerPoints = XPUtil.getTotalXPPoints(worker);

        if (workerPoints < requiredPoints) {
            int requiredLevels = XPUtil.pointsToLevels(requiredPoints);
            worker.sendMessage(plugin.getConfigManager().getMessage("xp-services.not-enough-levels")
                    .replace("{levels}", String.valueOf(requiredLevels)));
            return;
        }

        // Drain XP
        XPUtil.drainXP(worker, requiredPoints);

        // Give XP to contractor if online, else bottle it into mail (money only here)
        giveXPToContractor(contract, requiredPoints);

        // Pay the worker
        completeXPContract(contract, worker);
        worker.sendMessage(plugin.getConfigManager().getMessage("xp-services.drain-complete"));
    }

    // ---- Active Grind ----

    /**
     * Called when a worker accepts an ACTIVE_GRIND contract.
     * Gives them a Soul Bottle.
     */
    public void startActiveGrind(Player worker, Contract contract) {
        activeGrindSessions.put(worker.getUniqueId(), contract.getId());
        grindProgress.put(worker.getUniqueId(), 0);

        ItemStack bottle = createSoulBottle(contract.getId(), 0, MetadataUtil.getXPPoints(contract.getMetadata()));
        worker.getInventory().addItem(bottle);

        worker.sendMessage(plugin.getConfigManager().getMessage("xp-services.bottle-given")
                .replace("{id}", String.valueOf(contract.getId())));
    }

    /**
     * Called from MobKillListener: adds XP to grind progress.
     */
    public void addGrindXP(Player worker, int xpPoints) {
        Integer contractId = activeGrindSessions.get(worker.getUniqueId());
        if (contractId == null) return;

        Contract contract = plugin.getContractManager().getContract(contractId).orElse(null);
        if (contract == null) {
            activeGrindSessions.remove(worker.getUniqueId());
            return;
        }

        int required = MetadataUtil.getXPPoints(contract.getMetadata());
        int current = grindProgress.merge(worker.getUniqueId(), xpPoints, Integer::sum);

        // Update bottle in inventory
        updateSoulBottle(worker, contractId, Math.min(current, required), required);

        if (current >= required) {
            worker.sendMessage(plugin.getConfigManager().getMessage("xp-services.bottle-filled"));
        }
    }

    private void handleActiveGrindSubmit(Player worker, Contract contract, int required) {
        Integer current = grindProgress.get(worker.getUniqueId());
        if (current == null || current < required) {
            worker.sendMessage(plugin.getConfigManager().colorize(
                    "&cYour Soul Bottle is not full yet. (&a" + (current == null ? 0 : current) + "&c/&a" + required + "&c points)"));
            return;
        }

        // Remove soul bottle from inventory
        removeSoulBottle(worker, contract.getId());

        activeGrindSessions.remove(worker.getUniqueId());
        grindProgress.remove(worker.getUniqueId());

        giveXPToContractor(contract, required);
        completeXPContract(contract, worker);
    }

    private void giveXPToContractor(Contract contract, int xpPoints) {
        Player contractor = Bukkit.getPlayer(contract.getContractorUUID());
        if (contractor != null) {
            XPUtil.giveXP(contractor, xpPoints);
        }
        // If offline, XP cannot easily be stored — we log it and can add a mail note
        // (XP delivery to offline players is a known limitation; notify via mail message)
    }

    private void completeXPContract(Contract contract, Player worker) {
        plugin.getContractManager().completeContract(contract, worker);
        worker.sendMessage(plugin.getConfigManager().getMessage("xp-services.submitted")
                .replace("{reward}", String.format("%.2f", contract.getReward())));
    }

    // ---- Soul Bottle NBT ----

    private ItemStack createSoulBottle(int contractId, int current, int max) {
        ConfigManager cfg = plugin.getConfigManager();
        Material mat;
        try {
            mat = Material.valueOf(cfg.getSoulBottleItem().toUpperCase());
        } catch (IllegalArgumentException e) {
            mat = Material.GLASS_BOTTLE;
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(cfg.colorize(cfg.getSoulBottleName()));

        List<String> lore = cfg.getSoulBottleLore().stream()
                .map(l -> cfg.colorize(l
                        .replace("{contract_id}", String.valueOf(contractId))
                        .replace("{current}", String.valueOf(current))
                        .replace("{required}", String.valueOf(max))))
                .toList();
        meta.setLore(lore);

        // Store NBT
        NamespacedKey key = new NamespacedKey(plugin, SOUL_BOTTLE_KEY);
        NamespacedKey cKey = new NamespacedKey(plugin, SOUL_BOTTLE_CONTRACT_KEY);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BOOLEAN, true);
        meta.getPersistentDataContainer().set(cKey, PersistentDataType.INTEGER, contractId);

        item.setItemMeta(meta);
        return item;
    }

    private void updateSoulBottle(Player worker, int contractId, int current, int max) {
        NamespacedKey key = new NamespacedKey(plugin, SOUL_BOTTLE_KEY);
        NamespacedKey cKey = new NamespacedKey(plugin, SOUL_BOTTLE_CONTRACT_KEY);

        for (ItemStack is : worker.getInventory().getContents()) {
            if (is == null || !is.hasItemMeta()) continue;
            ItemMeta m = is.getItemMeta();
            if (m.getPersistentDataContainer().has(key, PersistentDataType.BOOLEAN)) {
                Integer storedId = m.getPersistentDataContainer().get(cKey, PersistentDataType.INTEGER);
                if (storedId != null && storedId == contractId) {
                    is.setItemMeta(createSoulBottle(contractId, current, max).getItemMeta());
                    return;
                }
            }
        }
    }

    private void removeSoulBottle(Player worker, int contractId) {
        NamespacedKey key = new NamespacedKey(plugin, SOUL_BOTTLE_KEY);
        NamespacedKey cKey = new NamespacedKey(plugin, SOUL_BOTTLE_CONTRACT_KEY);

        ItemStack[] contents = worker.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack is = contents[i];
            if (is == null || !is.hasItemMeta()) continue;
            ItemMeta m = is.getItemMeta();
            if (m.getPersistentDataContainer().has(key, PersistentDataType.BOOLEAN)) {
                Integer storedId = m.getPersistentDataContainer().get(cKey, PersistentDataType.INTEGER);
                if (storedId != null && storedId == contractId) {
                    contents[i] = null;
                }
            }
        }
        worker.getInventory().setContents(contents);
    }

    /**
     * Checks if an ItemStack is a Soul Bottle for a specific contract.
     */
    public boolean isSoulBottle(ItemStack item, int contractId) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta m = item.getItemMeta();
        NamespacedKey key = new NamespacedKey(plugin, SOUL_BOTTLE_KEY);
        NamespacedKey cKey = new NamespacedKey(plugin, SOUL_BOTTLE_CONTRACT_KEY);
        if (!m.getPersistentDataContainer().has(key, PersistentDataType.BOOLEAN)) return false;
        Integer storedId = m.getPersistentDataContainer().get(cKey, PersistentDataType.INTEGER);
        return storedId != null && storedId == contractId;
    }

    public java.util.Map<UUID, Integer> getActiveGrindSessions() { return activeGrindSessions; }
}
