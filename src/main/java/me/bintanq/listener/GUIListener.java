package me.bintanq.listener;

import me.bintanq.ContractBoard;
import me.bintanq.gui.GUIState;
import me.bintanq.manager.GUIManager.CBInventoryHolder;
import me.bintanq.model.Contract;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Central GUI listener using a State-pattern dispatch.
 *
 * FIXED BUGS:
 * - Contractor can't submit their own contract
 * - Added ongoing contracts view for workers
 */
public class GUIListener implements Listener {

    private final ContractBoard plugin;

    // Tracks which contract is being viewed per player: uuid -> contractId
    private final java.util.Map<java.util.UUID, Integer> viewingContract = new java.util.HashMap<>();
    // Tracks which contract type list is open: uuid -> ContractType
    private final java.util.Map<java.util.UUID, Contract.ContractType> viewingType = new java.util.HashMap<>();
    // Current page: uuid -> page
    private final java.util.Map<java.util.UUID, Integer> currentPage = new java.util.HashMap<>();

    public GUIListener(ContractBoard plugin) {
        this.plugin = plugin;
    }

    // ---- Block item movement in all plugin GUIs ----

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Check if this is our inventory
        Inventory topInv = event.getView().getTopInventory();
        if (!isCBInventory(topInv)) return;

        // CRITICAL: Cancel event to prevent item duplication/theft
        event.setCancelled(true);

        // Only process clicks in the TOP inventory (our GUI)
        if (event.getClickedInventory() == null) return;
        if (!isCBInventory(event.getClickedInventory())) return;

        // Prevent any item from being taken out
        if (event.getClick().isShiftClick()) return;
        if (event.getHotbarButton() >= 0) return;

        int slot = event.getSlot();
        GUIState state = plugin.getGUIManager().getState(player.getUniqueId());

        // If state is NONE, try to recover it from inventory holder
        if (state == GUIState.NONE) {
            state = recoverState(topInv);
            if (state != GUIState.NONE) {
                plugin.getGUIManager().setState(player.getUniqueId(), state);
            }
        }

        switch (state) {
            case MAIN_BOARD -> handleMainBoard(player, slot);
            case CONTRACT_LIST -> handleContractList(player, slot, topInv);
            case CONTRACT_DETAIL -> handleContractDetail(player, slot);
            case LEADERBOARD -> handleLeaderboard(player, slot);
            case MAIL -> handleMail(player, slot);
            default -> {} // NONE or unknown, ignore
        }
    }

    /**
     * Tries to recover the GUI state from inventory holder when state is lost
     */
    private GUIState recoverState(Inventory inv) {
        if (inv == null || !(inv.getHolder() instanceof CBInventoryHolder holder)) {
            return GUIState.NONE;
        }

        String id = holder.getId();
        if (id.equals("MAIN_BOARD")) return GUIState.MAIN_BOARD;
        if (id.equals("LEADERBOARD")) return GUIState.LEADERBOARD;
        if (id.equals("MAIL")) return GUIState.MAIL;
        if (id.startsWith("CONTRACT_LIST_")) return GUIState.CONTRACT_LIST;
        if (id.startsWith("CONTRACT_DETAIL_")) return GUIState.CONTRACT_DETAIL;

        return GUIState.NONE;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!isCBInventory(event.getInventory())) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!isCBInventory(event.getInventory())) return;

        // Don't clear state immediately - let it persist for a moment
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof CBInventoryHolder) {
                return;
            }
            plugin.getGUIManager().clearState(player.getUniqueId());
            viewingContract.remove(player.getUniqueId());
            viewingType.remove(player.getUniqueId());
            currentPage.remove(player.getUniqueId());
        }, 2L);
    }

    // ---- State Handlers ----

    private void handleMainBoard(Player player, int slot) {
        // Retrieve slots from gui.yml
        int bountySlot = getButtonSlot("main-board", "buttons.bounty-hunt", 20);
        int itemSlot = getButtonSlot("main-board", "buttons.item-gathering", 22);
        int xpSlot = getButtonSlot("main-board", "buttons.xp-services", 24);
        int lbSlot = getButtonSlot("main-board", "buttons.leaderboard", 49);
        int mailSlot = getButtonSlot("main-board", "buttons.mail", 45);
        int closeSlot = getButtonSlot("main-board", "buttons.close", 53);
        int statsSlot = getButtonSlot("main-board", "buttons.player-stats", 4); // NEW: Player stats button
        int myContractsSlot = getButtonSlot("main-board", "buttons.my-contracts", 48); // NEW: My contracts button

        if (slot == bountySlot) {
            if (!plugin.getConfigManager().isBountyEnabled()) {
                player.sendMessage(plugin.getConfigManager().getMessage("feature-disabled"));
                return;
            }
            openList(player, Contract.ContractType.BOUNTY_HUNT);
        } else if (slot == itemSlot) {
            if (!plugin.getConfigManager().isItemGatheringEnabled()) {
                player.sendMessage(plugin.getConfigManager().getMessage("feature-disabled"));
                return;
            }
            openList(player, Contract.ContractType.ITEM_GATHERING);
        } else if (slot == xpSlot) {
            if (!plugin.getConfigManager().isXPServiceEnabled()) {
                player.sendMessage(plugin.getConfigManager().getMessage("feature-disabled"));
                return;
            }
            openList(player, Contract.ContractType.XP_SERVICE);
        } else if (slot == lbSlot) {
            plugin.getGUIManager().openLeaderboard(player);
        } else if (slot == mailSlot) {
            plugin.getGUIManager().openMailGUI(player);
        } else if (slot == statsSlot) {
            // NEW: Open player stats GUI
            plugin.getGUIManager().openPlayerStats(player);
        } else if (slot == myContractsSlot) {
            // NEW: Open my contracts GUI (ongoing contracts)
            plugin.getGUIManager().openMyContracts(player);
        } else if (slot == closeSlot) {
            player.closeInventory();
        }
    }

    private void openList(Player player, Contract.ContractType type) {
        viewingType.put(player.getUniqueId(), type);
        currentPage.put(player.getUniqueId(), 0);
        plugin.getGUIManager().openContractList(player, type, 0);
    }

    private void handleContractList(Player player, int slot, Inventory inv) {
        int backSlot = getButtonSlot("contract-list", "navigation.back", 49);
        int prevSlot = getButtonSlot("contract-list", "navigation.prev-page", 45);
        int nextSlot = getButtonSlot("contract-list", "navigation.next-page", 53);
        int newSlot = getButtonSlot("contract-list", "navigation.new-contract", 47);

        Contract.ContractType type = viewingType.getOrDefault(player.getUniqueId(), Contract.ContractType.BOUNTY_HUNT);
        int page = currentPage.getOrDefault(player.getUniqueId(), 0);

        if (slot == backSlot) {
            plugin.getGUIManager().openMainBoard(player);
        } else if (slot == prevSlot && page > 0) {
            currentPage.put(player.getUniqueId(), page - 1);
            plugin.getGUIManager().openContractList(player, type, page - 1);
        } else if (slot == nextSlot) {
            currentPage.put(player.getUniqueId(), page + 1);
            plugin.getGUIManager().openContractList(player, type, page + 1);
        } else if (slot == newSlot) {
            player.closeInventory();
            player.sendMessage(plugin.getConfigManager().getMessage("contract.use-command"));
        } else if (slot < 45) {
            // Clicked a contract entry
            java.util.List<Contract> contracts = plugin.getContractManager().getOpenContracts(type);
            int index = page * 45 + slot;
            if (index < contracts.size()) {
                Contract c = contracts.get(index);
                viewingContract.put(player.getUniqueId(), c.getId());
                plugin.getGUIManager().openContractDetail(player, c);
            }
        }
    }

    private void handleContractDetail(Player player, int slot) {
        int acceptSlot = getButtonSlot("contract-detail", "buttons.accept", 20);
        int cancelSlot = getButtonSlot("contract-detail", "buttons.cancel", 24);
        int submitSlot = getButtonSlot("contract-detail", "buttons.submit", 22);
        int backSlot = getButtonSlot("contract-detail", "buttons.back", 31);

        Integer contractId = viewingContract.get(player.getUniqueId());
        if (contractId == null) {
            plugin.getGUIManager().openMainBoard(player);
            return;
        }

        Contract contract = plugin.getContractManager().getContract(contractId).orElse(null);
        if (contract == null) {
            plugin.getGUIManager().openMainBoard(player);
            return;
        }

        boolean isContractor = player.getUniqueId().equals(contract.getContractorUUID());
        boolean isWorker = player.getUniqueId().equals(contract.getWorkerUUID());

        if (slot == backSlot) {
            Contract.ContractType type = viewingType.getOrDefault(player.getUniqueId(), Contract.ContractType.BOUNTY_HUNT);
            plugin.getGUIManager().openContractList(player, type, currentPage.getOrDefault(player.getUniqueId(), 0));
        } else if (slot == acceptSlot) {
            // FIX BUG #1: Contractor cannot accept own contract
            if (isContractor) {
                player.sendMessage(plugin.getConfigManager().getMessage("contract.cannot-self"));
                return;
            }

            plugin.getContractManager().acceptContract(player, contractId);
            if (contract.getStatus() == Contract.ContractStatus.ACCEPTED) {
                handlePostAccept(player, contract);
            }
            player.closeInventory();
        } else if (slot == cancelSlot) {
            // Only contractor can cancel
            if (!isContractor) {
                player.sendMessage(plugin.getConfigManager().getMessage("contract.not-yours"));
                return;
            }

            plugin.getContractManager().cancelContract(player, contractId);
            player.closeInventory();
        } else if (slot == submitSlot) {
            // FIX BUG #1: Only worker can submit, NOT contractor
            if (!isWorker) {
                player.sendMessage(plugin.getConfigManager().getMessage("contract.not-worker"));
                return;
            }

            handleSubmit(player, contract);
            player.closeInventory();
        }
    }

    private void handlePostAccept(Player player, Contract contract) {
        switch (contract.getType()) {
            case BOUNTY_HUNT -> plugin.getBountyManager().startTracking(player, contract);
            case XP_SERVICE -> {
                String mode = me.bintanq.util.MetadataUtil.getXPMode(contract.getMetadata());
                if ("ACTIVE_GRIND".equals(mode)) {
                    plugin.getXPServiceManager().startActiveGrind(player, contract);
                }
            }
            default -> {}
        }
    }

    private void handleSubmit(Player player, Contract contract) {
        switch (contract.getType()) {
            case ITEM_GATHERING -> plugin.getItemGatheringManager().submitItems(player, contract);
            case XP_SERVICE -> plugin.getXPServiceManager().submitXP(player, contract);
            case BOUNTY_HUNT -> player.sendMessage(plugin.getConfigManager().getMessage("bounty.kill-to-complete"));
        }
    }

    private void handleLeaderboard(Player player, int slot) {
        int backSlot = getButtonSlot("leaderboard", "buttons.back", 49);
        if (slot == backSlot) {
            plugin.getGUIManager().openMainBoard(player);
        }
    }

    private void handleMail(Player player, int slot) {
        int backSlot = getButtonSlot("mail-gui", "buttons.back", 45);
        int collectAllSlot = getButtonSlot("mail-gui", "buttons.collect-all", 49);

        if (slot == backSlot) {
            plugin.getGUIManager().openMainBoard(player);
        } else if (slot == collectAllSlot) {
            plugin.getMailManager().collectAllMail(player);
            player.closeInventory();
        }
    }

    // ---- Utilities ----

    private boolean isCBInventory(Inventory inv) {
        if (inv == null) return false;
        InventoryHolder holder = inv.getHolder();
        return holder instanceof CBInventoryHolder;
    }

    private int getButtonSlot(String section, String buttonPath, int defaultSlot) {
        String fullPath = section + "." + buttonPath + ".slot";
        return plugin.getConfigManager().getGuiConfig().getInt(fullPath, defaultSlot);
    }
}