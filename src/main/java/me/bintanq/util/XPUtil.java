package me.bintanq.util;

import org.bukkit.entity.Player;

/**
 * Accurate Minecraft XP math utilities.
 *
 * Minecraft XP Formulas (Java Edition 1.21):
 *   Levels 0-15: xpToNextLevel = 2*level + 7
 *   Levels 16-30: xpToNextLevel = 5*level - 38
 *   Levels 31+: xpToNextLevel = 9*level - 158
 *
 * Total XP to reach level N:
 *   0-16: N^2 + 6N
 *   17-31: 2.5N^2 - 40.5N + 360
 *   32+: 4.5N^2 - 162.5N + 2220
 */
public final class XPUtil {

    private XPUtil() {}

    /**
     * Returns total XP points needed to reach a given level from level 0.
     */
    public static int getTotalXPToLevel(int level) {
        if (level <= 0) return 0;
        if (level <= 16) {
            return level * level + 6 * level;
        } else if (level <= 31) {
            return (int) (2.5 * level * level - 40.5 * level + 360);
        } else {
            return (int) (4.5 * level * level - 162.5 * level + 2220);
        }
    }

    /**
     * Returns XP needed to advance from level N to level N+1.
     */
    public static int xpToNextLevel(int level) {
        if (level <= 15) return 2 * level + 7;
        if (level <= 30) return 5 * level - 38;
        return 9 * level - 158;
    }

    /**
     * Returns the total XP points a player currently has (including partial level progress).
     */
    public static int getTotalXPPoints(Player player) {
        int level = player.getLevel();
        float progress = player.getExp(); // 0.0 - 1.0 within current level
        int baseXP = getTotalXPToLevel(level);
        int xpInLevel = (int) (progress * xpToNextLevel(level));
        return baseXP + xpInLevel;
    }

    /**
     * Converts a total XP point amount to the equivalent level (floored).
     */
    public static int pointsToLevels(int points) {
        int level = 0;
        while (getTotalXPToLevel(level + 1) <= points) {
            level++;
        }
        return level;
    }

    /**
     * Drains a given number of XP points from a player.
     * Sets the player's level and progress correctly.
     */
    public static void drainXP(Player player, int points) {
        int currentTotal = getTotalXPPoints(player);
        int remaining = Math.max(0, currentTotal - points);
        setTotalXP(player, remaining);
    }

    /**
     * Gives a player a given number of XP points.
     */
    public static void giveXP(Player player, int points) {
        player.giveExp(points);
    }

    /**
     * Sets a player's total XP to an exact point value.
     */
    public static void setTotalXP(Player player, int totalPoints) {
        // Find the level
        int level = 0;
        while (getTotalXPToLevel(level + 1) <= totalPoints) {
            level++;
        }
        int remainder = totalPoints - getTotalXPToLevel(level);
        float progress = (xpToNextLevel(level) == 0) ? 0f : (float) remainder / xpToNextLevel(level);

        player.setLevel(level);
        player.setExp(Math.max(0f, Math.min(1f, progress)));
    }
}
