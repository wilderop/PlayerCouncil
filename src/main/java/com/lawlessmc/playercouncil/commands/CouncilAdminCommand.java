package com.lawlessmc.playercouncil.commands;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.List;

public class CouncilAdminCommand implements CommandExecutor {

    private final PlayerCouncilPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public CouncilAdminCommand(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("playercouncil.admin")) {
            sender.sendMessage(mm.deserialize("<red>No permission."));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(mm.deserialize("<gold>/counciladmin reload</gold> - reload config"));
            sender.sendMessage(mm.deserialize("<gold>/counciladmin recalc</gold> - force recalculate council"));
            sender.sendMessage(mm.deserialize("<gold>/counciladmin setsize <n></gold>"));
            sender.sendMessage(mm.deserialize("<gold>/counciladmin sethours <n></gold>"));
            sender.sendMessage(mm.deserialize("<gold>/counciladmin setmin <n></gold>"));
            sender.sendMessage(mm.deserialize("<gold>/counciladmin whitelist add|remove|list <plugin></gold>"));
            sender.sendMessage(mm.deserialize("<gold>/counciladmin audit [limit]</gold>"));
            sender.sendMessage(mm.deserialize("<gold>/counciladmin stat list|add|remove|setweight|setscale</gold>"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadConfig();
                plugin.getTrackedStats().reload();
                sender.sendMessage(mm.deserialize("<green>Config reloaded (including tracked stats)."));
            }
            case "recalc" -> {
                plugin.getCouncilManager().recalculateCouncil();
                sender.sendMessage(mm.deserialize("<green>Council recalculation started (async)."));
            }
            case "setsize" -> {
                if (args.length < 2) { sender.sendMessage(mm.deserialize("<red>Usage: /counciladmin setsize <n>")); return true; }
                try {
                    int size = Integer.parseInt(args[1]);
                    plugin.getConfig().set("council.size", size);
                    plugin.saveConfig();
                    sender.sendMessage(mm.deserialize("<green>Council size set to " + size + ". Run /counciladmin recalc to apply."));
                } catch (NumberFormatException e) { sender.sendMessage(mm.deserialize("<red>Invalid number.")); }
            }
            case "sethours" -> {
                if (args.length < 2) { sender.sendMessage(mm.deserialize("<red>Usage: /counciladmin sethours <n>")); return true; }
                try {
                    int hours = Integer.parseInt(args[1]);
                    if (hours < 0) { sender.sendMessage(mm.deserialize("<red>Hours cannot be negative.")); return true; }
                    plugin.getConfig().set("council.min-total-hours", hours);
                    plugin.saveConfig();
                    sender.sendMessage(mm.deserialize("<green>Minimum total hours set to " + hours + ". Run /counciladmin recalc to apply."));
                } catch (NumberFormatException e) { sender.sendMessage(mm.deserialize("<red>Invalid number.")); }
            }
            case "setmin" -> {
                if (args.length < 2) { sender.sendMessage(mm.deserialize("<red>Usage: /counciladmin setmin <n>")); return true; }
                try {
                    int min = Integer.parseInt(args[1]);
                    if (min < 1) { sender.sendMessage(mm.deserialize("<red>Minimum must be at least 1.")); return true; }
                    plugin.getConfig().set("council.min-active-members", min);
                    plugin.saveConfig();
                    sender.sendMessage(mm.deserialize("<green>Minimum active members set to " + min + "."));
                    boolean active = plugin.getCouncilManager().isSystemActive();
                    sender.sendMessage(mm.deserialize(active ? "<green>Voting system is now ACTIVE." : "<yellow>Voting system is still INACTIVE (not enough members yet)."));
                } catch (NumberFormatException e) { sender.sendMessage(mm.deserialize("<red>Invalid number.")); }
            }
            case "whitelist" -> {
                if (args.length < 2) { sender.sendMessage(mm.deserialize("<red>Usage: /counciladmin whitelist <add|remove|list> [plugin]")); return true; }
                List<String> list = plugin.getConfig().getStringList("plugins.whitelist");
                switch (args[1].toLowerCase()) {
                    case "list" -> {
                        if (list.isEmpty()) sender.sendMessage(mm.deserialize("<gray>Whitelist is empty."));
                        else {
                            sender.sendMessage(mm.deserialize("<gold>Whitelisted plugins:"));
                            for (String s : list) sender.sendMessage(mm.deserialize("  <white>" + s));
                        }
                    }
                    case "add" -> {
                        if (args.length < 3) { sender.sendMessage(mm.deserialize("<red>Usage: /counciladmin whitelist add <plugin>")); return true; }
                        String name = args[2];
                        if (!list.contains(name)) { list.add(name); plugin.getConfig().set("plugins.whitelist", list); plugin.saveConfig(); }
                        sender.sendMessage(mm.deserialize("<green>Added " + name + " to whitelist."));
                    }
                    case "remove" -> {
                        if (args.length < 3) { sender.sendMessage(mm.deserialize("<red>Usage: /counciladmin whitelist remove <plugin>")); return true; }
                        String name = args[2];
                        list.removeIf(s -> s.equalsIgnoreCase(name));
                        plugin.getConfig().set("plugins.whitelist", list);
                        plugin.saveConfig();
                        sender.sendMessage(mm.deserialize("<green>Removed " + name + " from whitelist."));
                    }
                    default -> sender.sendMessage(mm.deserialize("<red>Unknown subcommand."));
                }
            }
            case "stat", "stats" -> handleStat(sender, args);
            case "audit" -> {
                int limit = 20;
                if (args.length >= 2) { try { limit = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {} }
                plugin.getDatabaseManager().getRecentAuditAsync(limit).thenAccept(logs ->
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            sender.sendMessage(mm.deserialize("<gold>===== Audit Log (last " + limit + ") ====="));
                            for (String line : logs) sender.sendMessage(mm.deserialize("<gray>" + line));
                        }));
            }
            default -> sender.sendMessage(mm.deserialize("<red>Unknown subcommand."));
        }
        return true;
    }

    private void handleStat(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(mm.deserialize("<red>Usage: /counciladmin stat <list|add|remove|setweight|setscale> ..."));
            return;
        }
        var tracked = plugin.getTrackedStats();
        switch (args[1].toLowerCase()) {
            case "list" -> {
                sender.sendMessage(mm.deserialize("<gold>Tracked activity statistics:"));
                for (var d : tracked.getDefinitions()) {
                    sender.sendMessage(mm.deserialize("  <white>" + d.name() + " <gray>weight=<aqua>" + d.weight() + " <gray>scale=<aqua>" + d.scale()));
                }
            }
            case "add" -> {
                if (args.length < 3) {
                    sender.sendMessage(mm.deserialize("<red>Usage: /counciladmin stat add <STATISTIC> [weight] [scale]"));
                    sender.sendMessage(mm.deserialize("<gray>Only UNTYPED stats work (e.g. DEATHS, PLAYER_KILLS, JUMP)."));
                    return;
                }
                double weight = 1.0;
                double scale = -1;
                if (args.length >= 4) {
                    try { weight = Double.parseDouble(args[3]); } catch (NumberFormatException e) {
                        sender.sendMessage(mm.deserialize("<red>Invalid weight.")); return;
                    }
                }
                if (args.length >= 5) {
                    try { scale = Double.parseDouble(args[4]); } catch (NumberFormatException e) {
                        sender.sendMessage(mm.deserialize("<red>Invalid scale.")); return;
                    }
                }
                if (!tracked.add(args[2], weight, scale)) {
                    sender.sendMessage(mm.deserialize("<red>Could not add stat. Must be a valid UNTYPED Bukkit Statistic name."));
                    return;
                }
                sender.sendMessage(mm.deserialize("<green>Added tracked stat <white>" + args[2].toUpperCase()
                        + "</white>. New snapshots will include it after join/quit pairs."));
            }
            case "remove" -> {
                if (args.length < 3) { sender.sendMessage(mm.deserialize("<red>Usage: /counciladmin stat remove <STATISTIC>")); return; }
                if (!tracked.remove(args[2])) { sender.sendMessage(mm.deserialize("<red>Stat not in tracked list.")); return; }
                sender.sendMessage(mm.deserialize("<green>Removed tracked stat <white>" + args[2].toUpperCase()));
            }
            case "setweight" -> {
                if (args.length < 4) { sender.sendMessage(mm.deserialize("<red>Usage: /counciladmin stat setweight <STATISTIC> <weight>")); return; }
                double weight;
                try { weight = Double.parseDouble(args[3]); } catch (NumberFormatException e) {
                    sender.sendMessage(mm.deserialize("<red>Invalid weight.")); return;
                }
                if (!tracked.setWeight(args[2], weight)) {
                    sender.sendMessage(mm.deserialize("<red>Stat not in tracked list. Add it first.")); return;
                }
                sender.sendMessage(mm.deserialize("<green>Updated weight for <white>" + args[2].toUpperCase()));
            }
            case "setscale" -> {
                if (args.length < 4) {
                    sender.sendMessage(mm.deserialize("<red>Usage: /counciladmin stat setscale <STATISTIC> <scale>"));
                    sender.sendMessage(mm.deserialize("<gray>Score uses weight * (delta / scale). Use 1 for raw counts."));
                    return;
                }
                double scale;
                try { scale = Double.parseDouble(args[3]); } catch (NumberFormatException e) {
                    sender.sendMessage(mm.deserialize("<red>Invalid scale.")); return;
                }
                if (scale <= 0) { sender.sendMessage(mm.deserialize("<red>Scale must be positive.")); return; }
                if (!tracked.setScale(args[2], scale)) {
                    sender.sendMessage(mm.deserialize("<red>Stat not in tracked list. Add it first.")); return;
                }
                sender.sendMessage(mm.deserialize("<green>Updated scale for <white>" + args[2].toUpperCase()));
            }
            default -> sender.sendMessage(mm.deserialize("<red>Unknown stat subcommand. Use list, add, remove, setweight, setscale."));
        }
    }
}
