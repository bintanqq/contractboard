package me.bintanq.command;

import me.bintanq.ContractBoard;
import me.bintanq.model.Contract;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles the /contract command and all subcommands.
 * Subcommands:
 *   open          - Opens the main contract board GUI
 *   reload        - Reloads the plugin (admin)
 *   mail          - Collects all mail
 *   top           - Shows leaderboards via GUI
 *   post bounty <target> <reward> [anonymous]
 *   post item <material> <amount> <reward>
 *   post xp <points> <reward> <INSTANT_DRAIN|ACTIVE_GRIND>
 *   cancel <id>
 *   claim <id>
 *   help
 */
public class ContractCommand implements CommandExecutor, TabCompleter {

    private final ContractBoard plugin;

    public ContractCommand(ContractBoard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "open" -> {
                if (!requirePlayer(sender)) return true;
                if (!requirePerm(sender, "contractboard.use")) return true;
                plugin.getGUIManager().openMainBoard((Player) sender);
            }

            case "reload" -> {
                if (!requirePerm(sender, "contractboard.admin")) return true;
                plugin.reload();
                sender.sendMessage(plugin.getConfigManager().getMessage("plugin-reloaded"));
            }

            case "mail" -> {
                if (!requirePlayer(sender)) return true;
                if (!requirePerm(sender, "contractboard.use")) return true;
                plugin.getMailManager().collectAllMail((Player) sender);
            }

            case "top" -> {
                if (!requirePlayer(sender)) return true;
                if (!requirePerm(sender, "contractboard.use")) return true;
                plugin.getGUIManager().openLeaderboard((Player) sender);
            }

            case "post" -> {
                if (!requirePlayer(sender)) return true;
                if (!requirePerm(sender, "contractboard.use")) return true;
                handlePost((Player) sender, args);
            }

            case "cancel" -> {
                if (!requirePlayer(sender)) return true;
                if (!requirePerm(sender, "contractboard.use")) return true;
                if (args.length < 2) { sender.sendMessage(usage("/contract cancel <id>")); return true; }
                try {
                    int id = Integer.parseInt(args[1]);
                    plugin.getContractManager().cancelContract((Player) sender, id);
                } catch (NumberFormatException e) {
                    sender.sendMessage(plugin.getConfigManager().colorize("&cInvalid contract ID."));
                }
            }

            case "claim" -> {
                if (!requirePlayer(sender)) return true;
                if (!requirePerm(sender, "contractboard.use")) return true;
                if (args.length < 2) { sender.sendMessage(usage("/contract claim <id>")); return true; }
                try {
                    int id = Integer.parseInt(args[1]);
                    plugin.getItemGatheringManager().claimItems((Player) sender, id);
                } catch (NumberFormatException e) {
                    sender.sendMessage(plugin.getConfigManager().colorize("&cInvalid contract ID."));
                }
            }

            case "help" -> sendHelp(sender);

            default -> sender.sendMessage(plugin.getConfigManager().getMessage("unknown-command"));
        }

        return true;
    }

    private void handlePost(Player player, String[] args) {
        if (args.length < 2) {
            sendHelp(player);
            return;
        }

        String type = args[1].toLowerCase();

        switch (type) {
            case "bounty" -> {
                // /contract post bounty <target> <reward> [anonymous]
                if (args.length < 4) { player.sendMessage(usage("/contract post bounty <target> <reward> [anonymous]")); return; }
                String targetName = args[2];
                double reward;
                try { reward = Double.parseDouble(args[3]); } catch (NumberFormatException e) {
                    player.sendMessage(plugin.getConfigManager().colorize("&cInvalid reward amount.")); return;
                }
                boolean anon = args.length >= 5 && args[4].equalsIgnoreCase("true");
                plugin.getBountyManager().postBounty(player, targetName, reward, anon);
            }

            case "item" -> {
                // /contract post item <material> <amount> <reward>
                if (args.length < 5) { player.sendMessage(usage("/contract post item <material> <amount> <reward>")); return; }
                Material mat;
                try { mat = Material.valueOf(args[2].toUpperCase()); } catch (IllegalArgumentException e) {
                    player.sendMessage(plugin.getConfigManager().colorize("&cInvalid material: " + args[2])); return;
                }
                int amount;
                try { amount = Integer.parseInt(args[3]); } catch (NumberFormatException e) {
                    player.sendMessage(plugin.getConfigManager().colorize("&cInvalid amount.")); return;
                }
                double reward;
                try { reward = Double.parseDouble(args[4]); } catch (NumberFormatException e) {
                    player.sendMessage(plugin.getConfigManager().colorize("&cInvalid reward amount.")); return;
                }
                plugin.getItemGatheringManager().postContract(player, mat, amount, reward);
            }

            case "xp" -> {
                // /contract post xp <points> <reward> <INSTANT_DRAIN|ACTIVE_GRIND>
                if (args.length < 5) { player.sendMessage(usage("/contract post xp <points> <reward> <INSTANT_DRAIN|ACTIVE_GRIND>")); return; }
                int points;
                try { points = Integer.parseInt(args[2]); } catch (NumberFormatException e) {
                    player.sendMessage(plugin.getConfigManager().colorize("&cInvalid XP point amount.")); return;
                }
                double reward;
                try { reward = Double.parseDouble(args[3]); } catch (NumberFormatException e) {
                    player.sendMessage(plugin.getConfigManager().colorize("&cInvalid reward amount.")); return;
                }
                String mode = args[4].toUpperCase();
                if (!mode.equals("INSTANT_DRAIN") && !mode.equals("ACTIVE_GRIND")) {
                    player.sendMessage(plugin.getConfigManager().colorize("&cMode must be INSTANT_DRAIN or ACTIVE_GRIND."));
                    return;
                }
                plugin.getXPServiceManager().postContract(player, points, reward, mode);
            }

            default -> sendHelp(player);
        }
    }

    private void sendHelp(CommandSender sender) {
        String c = plugin.getConfigManager().colorize("&8[&6ContractBoard&8] ");
        sender.sendMessage(c + "&6Commands:");
        sender.sendMessage("&e/contract open &7- Open the contract board GUI");
        sender.sendMessage("&e/contract post bounty <target> <reward> [true/false] &7- Post a bounty");
        sender.sendMessage("&e/contract post item <material> <amount> <reward> &7- Post item contract");
        sender.sendMessage("&e/contract post xp <points> <reward> <INSTANT_DRAIN|ACTIVE_GRIND> &7- Post XP contract");
        sender.sendMessage("&e/contract cancel <id> &7- Cancel your contract");
        sender.sendMessage("&e/contract claim <id> &7- Claim delivered items");
        sender.sendMessage("&e/contract mail &7- Collect your mail rewards");
        sender.sendMessage("&e/contract top &7- View leaderboards");
        if (sender.hasPermission("contractboard.admin")) {
            sender.sendMessage("&e/contract reload &7- Reload plugin config");
        }
    }

    private String usage(String usage) {
        return plugin.getConfigManager().colorize("&cUsage: " + usage);
    }

    // ---- Tab Completion ----

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            completions.addAll(Arrays.asList("open", "post", "cancel", "claim", "mail", "top", "help"));
            if (sender.hasPermission("contractboard.admin")) completions.add("reload");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("post")) {
            completions.addAll(Arrays.asList("bounty", "item", "xp"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("post")) {
            switch (args[1].toLowerCase()) {
                case "bounty" -> Bukkit.getOnlinePlayers().forEach(p -> completions.add(p.getName()));
                case "item" -> {
                    String input = args[2].toUpperCase();
                    Arrays.stream(Material.values())
                            .filter(m -> m.isItem() && !m.isAir())
                            .map(Enum::name)
                            .filter(n -> n.startsWith(input))
                            .limit(10)
                            .forEach(completions::add);
                }
                case "xp" -> completions.add("<points>");
            }
        } else if (args.length == 5 && args[0].equalsIgnoreCase("post") && args[1].equalsIgnoreCase("xp")) {
            completions.addAll(Arrays.asList("INSTANT_DRAIN", "ACTIVE_GRIND"));
        } else if (args.length == 5 && args[0].equalsIgnoreCase("post") && args[1].equalsIgnoreCase("bounty")) {
            completions.addAll(Arrays.asList("true", "false"));
        }

        // Filter by current input
        String current = args[args.length - 1].toLowerCase();
        return completions.stream()
                .filter(s -> s.toLowerCase().startsWith(current))
                .collect(Collectors.toList());
    }

    // ---- Guards ----

    private boolean requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("player-only"));
            return false;
        }
        return true;
    }

    private boolean requirePerm(CommandSender sender, String perm) {
        if (!sender.hasPermission(perm)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("no-permission"));
            return false;
        }
        return true;
    }
}
