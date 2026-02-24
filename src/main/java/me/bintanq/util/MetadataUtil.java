package me.bintanq.util;

/**
 * Handles serialization and deserialization of contract metadata strings.
 *
 * Formats:
 *  Bounty:        "targetUUID|targetName|anonymous"
 *  ItemGathering: "material|amount|submitted|workerUUID"
 *  XP:            "points|mode"
 */
public final class MetadataUtil {

    private static final String SEP = "|";

    private MetadataUtil() {}

    // ---- Bounty ----

    public static String buildBountyMeta(String targetUUID, String targetName, boolean anonymous) {
        return targetUUID + SEP + targetName + SEP + anonymous;
    }

    public static String getBountyTargetUUID(String meta) {
        return parts(meta)[0];
    }

    public static String getBountyTargetName(String meta) {
        return parts(meta)[1];
    }

    public static boolean isBountyAnonymous(String meta) {
        return Boolean.parseBoolean(parts(meta)[2]);
    }

    // ---- Item Gathering ----

    public static String buildItemMeta(String material, int amount, boolean submitted, String workerUUID) {
        return material + SEP + amount + SEP + submitted + SEP + (workerUUID == null ? "" : workerUUID);
    }

    public static String getItemMaterial(String meta) {
        return parts(meta)[0];
    }

    public static int getItemAmount(String meta) {
        try { return Integer.parseInt(parts(meta)[1]); } catch (NumberFormatException e) { return 0; }
    }

    public static boolean isItemSubmitted(String meta) {
        String[] p = parts(meta);
        return p.length > 2 && Boolean.parseBoolean(p[2]);
    }

    public static String getItemWorkerUUID(String meta) {
        String[] p = parts(meta);
        if (p.length < 4 || p[3].isEmpty()) return null;
        return p[3];
    }

    // ---- XP Services ----

    public static String buildXPMeta(int points, String mode) {
        return points + SEP + mode;
    }

    public static int getXPPoints(String meta) {
        try { return Integer.parseInt(parts(meta)[0]); } catch (NumberFormatException e) { return 0; }
    }

    public static String getXPMode(String meta) {
        String[] p = parts(meta);
        return p.length > 1 ? p[1] : "INSTANT_DRAIN";
    }

    // ---- Utility ----

    private static String[] parts(String meta) {
        if (meta == null) return new String[]{};
        return meta.split("\\|", -1);
    }
}