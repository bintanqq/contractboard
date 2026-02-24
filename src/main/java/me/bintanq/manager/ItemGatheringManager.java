package me.bintanq.manager;

import me.bintanq.ContractBoard;
import me.bintanq.model.Contract;
import me.bintanq.util.MetadataUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;

/**
 * Handles Item Gathering contract logic:
 * - Reward escrowed on contract creation (taken from contractor via ContractManager)
 * - Full-inventory check on submission (no partial deliveries)
 * - Contractor claim flow
 */
public class ItemGatheringManager {

    private final ContractBoard plugin;

    public ItemGatheringManager(ContractBoard plugin) {
        this.plugin = plugin;
    }

    /**
     * Posts a new Item Gathering contract.
     * Metadata format: "material|amount|submitted" where submitted = false initially
     */
    public void postContract(Player contractor, Material material, int amount, double reward) {
        String metadata = MetadataUtil.buildItemMeta(material.name(), amount, false, null);

        plugin.getContractManager().createContract(
                contractor,
                Contract.ContractType.ITEM_GATHERING,
                reward,
                metadata,
                contract -> {} // success already messaged by ContractManager
        );
    }

    /**
     * Called when a worker clicks "Submit" in the GUI.
     * Verifies the worker has the full required amount in inventory.
     * On success: removes items, marks submitted, notifies contractor.
     */
    public void submitItems(Player worker, Contract contract) {
        String materialName = MetadataUtil.getItemMaterial(contract.getMetadata());
        int required = MetadataUtil.getItemAmount(contract.getMetadata());

        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            worker.sendMessage(plugin.getConfigManager().getMessage("contract.not-found"));
            return;
        }

        // Count items in inventory
        int count = countItemsInInventory(worker, material);
        if (count < required) {
            worker.sendMessage(plugin.getConfigManager().getMessage("item-gathering.not-enough-items")
                    .replace("{item}", material.name())
                    .replace("{amount}", String.valueOf(required)));
            return;
        }

        // Remove items from inventory
        removeItemsFromInventory(worker, material, required);

        // Mark as submitted in metadata
        contract.setMetadata(MetadataUtil.buildItemMeta(
                materialName, required, true, worker.getUniqueId().toString()));
        plugin.getDatabaseManager().updateContract(contract);

        worker.sendMessage(plugin.getConfigManager().getMessage("item-gathering.submitted"));

        // Notify contractor if online
        Player contractor = Bukkit.getPlayer(contract.getContractorUUID());
        if (contractor != null) {
            contractor.sendMessage(plugin.getConfigManager().getMessage("item-gathering.items-ready")
                    .replace("{id}", String.valueOf(contract.getId())));
        }
    }

    /**
     * Contractor claims the delivered items.
     * Items are "virtual" — we just complete the contract and award the worker.
     */
    public void claimItems(Player contractor, int contractId) {
        Contract contract = plugin.getContractManager().getContract(contractId).orElse(null);

        if (contract == null || !contract.getContractorUUID().equals(contractor.getUniqueId())) {
            contractor.sendMessage(plugin.getConfigManager().getMessage("contract.not-found"));
            return;
        }

        if (!MetadataUtil.isItemSubmitted(contract.getMetadata())) {
            contractor.sendMessage(plugin.getConfigManager().colorize("&cItems have not been submitted yet."));
            return;
        }

        // Give contractor the items
        String materialName = MetadataUtil.getItemMaterial(contract.getMetadata());
        int amount = MetadataUtil.getItemAmount(contract.getMetadata());
        Material material = Material.valueOf(materialName);

        giveItems(contractor, material, amount);

        // Complete contract: pay the worker
        String workerUUIDStr = MetadataUtil.getItemWorkerUUID(contract.getMetadata());
        if (workerUUIDStr != null) {
            Player worker = Bukkit.getPlayer(UUID.fromString(workerUUIDStr));
            if (worker != null) {
                plugin.getContractManager().completeContract(contract, worker);
            } else {
                // Worker offline: send to mail
                contract.setStatus(Contract.ContractStatus.COMPLETED);
                plugin.getDatabaseManager().updateContract(contract);
                plugin.getDatabaseManager().insertMail(
                        UUID.fromString(workerUUIDStr),
                        contract.getReward(),
                        "Completed item gathering contract #" + contractId,
                        null
                );
            }
        }

        contractor.sendMessage(plugin.getConfigManager().getMessage("item-gathering.claimed")
                .replace("{amount}", String.valueOf(amount))
                .replace("{item}", material.name())
                .replace("{id}", String.valueOf(contractId)));
    }

    // ---- Inventory Utilities ----

    private int countItemsInInventory(Player player, Material material) {
        int count = 0;
        for (ItemStack is : player.getInventory().getContents()) {
            if (is != null && is.getType() == material) count += is.getAmount();
        }
        return count;
    }

    private void removeItemsFromInventory(Player player, Material material, int amount) {
        int remaining = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack is = contents[i];
            if (is != null && is.getType() == material) {
                int toRemove = Math.min(remaining, is.getAmount());
                is.setAmount(is.getAmount() - toRemove);
                remaining -= toRemove;
                if (is.getAmount() == 0) contents[i] = null;
            }
        }
        player.getInventory().setContents(contents);
    }

    private void giveItems(Player player, Material material, int amount) {
        while (amount > 0) {
            int stackSize = Math.min(amount, material.getMaxStackSize());
            ItemStack stack = new ItemStack(material, stackSize);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            // Drop any that don't fit
            leftover.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
            amount -= stackSize;
        }
    }

    // Workaround for Java type inference with Map.
    @SuppressWarnings("unchecked")
    private <K, V> java.util.Map<K, V> getMap() { return new java.util.HashMap<>(); }
}
