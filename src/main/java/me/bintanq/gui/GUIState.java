package me.bintanq.gui;

/**
 * Represents the state of a player's current GUI interaction.
 * Used by GUIListener to route click events appropriately.
 */
public enum GUIState {
    NONE,
    MAIN_BOARD,
    CONTRACT_LIST,      // Viewing a list of contracts (any type)
    CONTRACT_DETAIL,    // Viewing a specific contract
    LEADERBOARD,
    MAIL,
    CONFIRM_POST        // Confirmation dialog for creating a contract
}