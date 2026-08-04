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
            sender.sendMessage(mm.deserialize("<gold>/counciladmin setsize <n></gold> - set council size"));
            sender.sendMessage(mm.deserialize("<gold>/counciladmin sethours <n></gold> - set minimum total hours to qualify"));
            sender.sendMessage(mm.deserialize("<gold>/counciladmin setmin <n></gold> - set minimum active members before voting is enabled"));
            sender.sendMessage(mm.deserialize("<gold>/counciladmin whitelist add|remove|list <plugin></gold>"));
            sender.sendMessage(mm.deserialize("<gold>/counciladmin audit [limit]</gold> - show recent log"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage(mm.deserialize("<green>Config reloaded."));
            }
            case "recalc" -> {
                plugin.getCouncilManager().recalculateCouncil();
                sender.sendMessage(mm.deserialize("<green>Council recalculation started (async)."));
            }
            case "setsize" -> {
                if (args.length < 2) {
                    sender.sendMessage(mm.deserialize("<red>Usage: /counciladmin setsize <n>"));
                    return true;
                }
                try {
                    int size = Integer.parseInt(args[1]);
                    plugin.getConfig().set("council.size", size);
                    plugin.saveConfig();
                    sender.sendMessage(mm.deserialize("<green>Council size set to " + size + ". Run /counciladmin recalc to apply."));
                } catch (NumberFormatException e) {
                    sender.sendMessage(mm.deserialize("<red>Invalid number."));
                }
            }
            case "sethours" -> {
                if (args.length < 2) {
                    sender.sendMessage(mm.deserialize("<red>Usage: /counciladmin sethours <n>"));
                    return true;
                }
                try {
                    int hours = Integer.parseInt(args[1]);
                    if (hours < 0) {
                        sender.sendMessage(mm.deserialize("<red>Hours cannot be negative."));
                        return true;
                    }
                    plugin.getConfig().set("council.min-total-hours", hours);
                    plugin.saveConfig();
                    sender.sendMessage(mm.deserialize("<green>Minimum total hours set to " + hours + ". Run /counciladmin recalc to apply."));
                } catch (NumberFormatException e) {
                    sender.sendMessage(mm.deserialize("<red>Invalid number."));
                }
            }
            case "setmin" -> {
                if (args.length < 2) {
                    sender.sendMessage(mm.deserialize("<red>Usage: /counciladmin setmin <n>"));
                    return true;
                }
                try {
                    int min = Integer.parseInt(args[1]);
                    if (min < 1) {
                        sender.sendMessage(mm.deserialize("<red>Minimum must be at least 1."));
                        return true;
                    }
                    plugin.getConfig().set("council.min-active-members", min);
                    plugin.saveConfig();
                    sender.sendMessage(mm.deserialize("<green>Minimum active members set to " + min + "."));
                    boolean active = plugin.getCouncilManager().isSystemActive();
                    sender.sendMessage(mm.deserialize(active
                            ? "<green>Voting system is now ACTIVE."
                            : "<yellow>Voting system is still INACTIVE (not enough members yet)."));
                } catch (NumberFormatException e) {
                    sender.sendMessage(mm.deserialize("<red>Invalid number."));
                }
            }
            case "whitelist" -> {
                if (args.length < 2) {
                    sender.sendMessage(mm.deserialize("<red>Usage: /counciladmin whitelist <add|remove|list> [plugin]"));
                    return true;
                }
                List<String> list = plugin.getConfig().getStringList("plugins.whitelist");
                switch (args[1].toLowerCase()) {
                    case "list" -> {
                        if (list.isEmpty()) {
                            sender.sendMessage(mm.deserialize("<gray>Whitelist is empty."));
                        } else {
                            sender.sendMessage(mm.deserialize("<gold>Whitelisted plugins:"));
                            for (String s : list) {
                                sender.sendMessage(mm.deserialize("  <white>" + s));
                            }
                        }
                    }
                    case "add" -> {
                        if (args.length < 3) {
                            sender.sendMessage(mm.deserialize("<red>Usage: /counciladmin whitelist add <plugin>"));
                            return true;
                        }
                        String name = args[2];
                        if (!list.contains(name)) {
                            list.add(name);
                            plugin.getConfig().set("plugins.whitelist", list);
                            plugin.saveConfig();
                        }
                        sender.sendMessage(mm.deserialize("<green>Added " + name + " to whitelist."));
                    }
                    case "remove" -> {
                        if (args.length < 3) {
                            sender.sendMessage(mm.deserialize("<red>Usage: /counciladmin whitelist remove <plugin>"));
                            return true;
                        }
                        String name = args[2];
                        list.removeIf(s -> s.equalsIgnoreCase(name));
                        plugin.getConfig().set("plugins.whitelist", list);
                        plugin.saveConfig();
                        sender.sendMessage(mm.deserialize("<green>Removed " + name + " from whitelist."));
                    }
                    default -> sender.sendMessage(mm.deserialize("<red>Unknown subcommand."));
                }
            }
            case "audit" -> {
                int limit = 20;
                if (args.length >= 2) {
                    try { limit = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
                }
                plugin.getDatabaseManager().getRecentAuditAsync(limit).thenAccept(logs ->
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            sender.sendMessage(mm.deserialize("<gold>===== Audit Log (last " + limit + ") ====="));
                            for (String line : logs) {
                                sender.sendMessage(mm.deserialize("<gray>" + line));
                            }
                        }));
            }
            default -> sender.sendMessage(mm.deserialize("<red>Unknown subcommand."));
        }
        return true;
    }
}
