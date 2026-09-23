package com.lawlessmc.playercouncil.commands;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import com.lawlessmc.playercouncil.bridge.Actors;
import com.lawlessmc.playercouncil.bridge.BridgedSender;
import com.lawlessmc.playercouncil.models.BugReport;
import com.lawlessmc.playercouncil.util.CoordRedact;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class BugCommand implements CommandExecutor {

    private static final Set<String> CLOSE_REASONS = Set.of(
            "false", "spam", "invalid", "fixed", "wontfix", "duplicate");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final PlayerCouncilPlugin plugin;

    public BugCommand(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if ("bugnotify".equalsIgnoreCase(label)) {
            sendPending(sender);
            return true;
        }
        if (args.length == 0) {
            listActive(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> sendHelp(sender);
            case "report", "new", "add" -> report(sender, args, 1);
            case "reply" -> reply(sender, args);
            case "confirm", "still", "plus", "+1" -> confirm(sender, args);
            case "closed", "fixed" -> listClosed(sender);
            case "close" -> close(sender, args);
            case "reopen" -> reopen(sender, args);
            case "ask" -> ask(sender, args);
            case "block" -> block(sender, args, true);
            case "unblock" -> block(sender, args, false);
            default -> {
                if (sub.matches("\\d+")) {
                    show(sender, parseId(sub));
                } else {
                    sendHelp(sender);
                }
            }
        }
        return true;
    }

    public static void sendPendingTo(PlayerCouncilPlugin plugin, CommandSender sender) {
        new BugCommand(plugin).sendPending(sender);
    }

    private void sendHelp(CommandSender sender) {
        msg(sender, "<gold>===== Bugs =====");
        msg(sender, "<yellow>/bug</yellow> <gray>— open bugs");
        msg(sender, "<yellow>/bug report <text></yellow> <gray>— file a new one");
        msg(sender, "<yellow>/bug <id></yellow> <gray>— details");
        msg(sender, "<yellow>/bug reply <id> <text></yellow> <gray>— add context");
        msg(sender, "<yellow>/bug confirm <id></yellow> <gray>— this is still happening");
        msg(sender, "<yellow>/bug closed</yellow> <gray>— recently closed");
        if (canModerate(sender)) {
            msg(sender, "<gray>Staff: /bug close <id> [false|spam|fixed|wontfix] [note]");
            msg(sender, "<gray>Staff: /bug reopen <id> · /bug ask <id> <question>");
            msg(sender, "<gray>Staff: /bug block <player> [reason] · /bug unblock <player>");
        }
    }

    private void listActive(CommandSender sender) {
        UUID self = Actors.uuid(sender);
        int limit = plugin.getConfig().getInt("bugs.list-limit", 15);
        plugin.getDatabaseManager().listActiveBugsAsync(limit).thenAccept(list ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (self != null) {
                        for (BugReport b : list) {
                            if (self.equals(b.reporterUuid) && BugReport.NEEDS_INFO.equals(b.status)
                                    && b.question != null && !b.question.isBlank()) {
                                msg(sender, "<gold>Your bug <yellow>#" + b.id
                                        + "</yellow> needs more info:</gold> <white>" + safe(b.question));
                                msg(sender, "<gray>Answer with <yellow>/bug reply " + b.id + " <text>");
                            }
                        }
                    }
                    msg(sender, "<gold>===== Open bugs (" + list.size() + ") =====");
                    if (list.isEmpty()) {
                        msg(sender, "<gray>None right now. File one with <yellow>/bug report <what broke>");
                        return;
                    }
                    for (BugReport b : list) {
                        String extra = b.reportCount > 1 ? " <gray>(" + b.reportCount + ")" : "";
                        String st = BugReport.OPEN.equals(b.status) ? "" :
                                " <aqua>[" + b.statusLabel() + "]";
                        msg(sender, "<click:run_command:'/bug " + b.id + "'><gold>#" + b.id
                                + "</gold></click> <white>" + clip(safe(b.title), 60) + extra + st);
                    }
                    msg(sender, "<gray>/bug <id> for details · <click:suggest_command:'/bug report '><yellow>/bug report <text></yellow></click>");
                }));
    }

    private void listClosed(CommandSender sender) {
        int limit = plugin.getConfig().getInt("bugs.list-limit", 15);
        plugin.getDatabaseManager().listClosedBugsAsync(limit).thenAccept(list ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    msg(sender, "<gold>===== Recently closed =====");
                    if (list.isEmpty()) {
                        msg(sender, "<gray>None yet.");
                        return;
                    }
                    for (BugReport b : list) {
                        msg(sender, "<click:run_command:'/bug " + b.id + "'><gold>#" + b.id
                                + "</gold></click> <gray>[" + b.statusLabel() + "]</gray> <white>"
                                + clip(safe(b.title), 50));
                    }
                }));
    }

    private void show(CommandSender sender, int id) {
        if (id <= 0) {
            msg(sender, "<red>Invalid bug id.");
            return;
        }
        plugin.getDatabaseManager().getBugAsync(id).thenAccept(b ->
                plugin.getDatabaseManager().listBugEventsAsync(id, 8).thenAccept(events ->
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (b == null) {
                                msg(sender, "<red>No bug #" + id + ".");
                                return;
                            }
                            msg(sender, "<gold>===== Bug #" + b.id + " =====");
                            msg(sender, "<gray>Status:</gray> <aqua>" + b.statusLabel()
                                    + "</aqua> <gray>· " + ago(b.createdAt)
                                    + " · " + b.reportCount + " report" + (b.reportCount == 1 ? "" : "s"));
                            msg(sender, "<white>" + safe(b.title));
                            msg(sender, "<gray>Reporter:</gray> <white>" + safe(b.reporterName));
                            if (b.question != null && !b.question.isBlank() && b.isActive()) {
                                msg(sender, "<gold>Question:</gold> <white>" + safe(b.question));
                                msg(sender, "<gray>Answer: <yellow>/bug reply " + b.id + " <text>");
                            }
                            if (b.closeNote != null && !b.closeNote.isBlank() && !b.isActive()) {
                                String by = b.closedByName != null ? b.closedByName : "?";
                                msg(sender, "<gray>Closed by " + safe(by) + ":</gray> <white>" + safe(b.closeNote));
                            }
                            for (BugReport.Event e : events) {
                                if ("report".equals(e.kind) || "confirm".equals(e.kind)) continue;
                                if (("note".equals(e.kind) || "nightly".equals(e.kind)) && !canModerate(sender)) continue;
                                String who = e.actorName != null && !e.actorName.isBlank() ? e.actorName : "system";
                                String body = e.text != null ? clip(safe(e.text), 80) : "";
                                msg(sender, "<dark_gray>" + ago(e.at) + "</dark_gray> <gray>"
                                        + e.kind + "</gray> <white>" + safe(who)
                                        + (body.isEmpty() ? "" : "<gray>: </gray>" + body));
                            }
                            if (b.isActive()) {
                                msg(sender, "<gray>/bug confirm " + b.id + " if this is still happening");
                            }
                        })));
    }

    private void report(CommandSender sender, String[] args, int from) {
        UUID uuid = Actors.uuid(sender);
        if (uuid == null) {
            sender.sendMessage("Players only.");
            return;
        }
        String name = Actors.name(sender);
        String text = join(args, from);
        int min = plugin.getConfig().getInt("bugs.min-length", 8);
        int max = plugin.getConfig().getInt("bugs.max-length", 240);
        if (text.isBlank()) {
            msg(sender, "<red>Usage: /bug report <what broke>");
            return;
        }
        if (text.length() < min) {
            msg(sender, "<red>Need at least " + min + " characters. Say what actually broke.");
            return;
        }
        if (text.length() > max) {
            msg(sender, "<red>Too long (" + text.length() + "/" + max + "). Shorten it.");
            return;
        }
        String title = sanitize(text);
        int cooldownSec = plugin.getConfig().getInt("bugs.cooldown-seconds", 600);
        int maxOpen = plugin.getConfig().getInt("bugs.max-open-per-player", 3);
        int maxDay = plugin.getConfig().getInt("bugs.max-per-day", 3);
        int minHours = plugin.getConfig().getInt("bugs.min-hours", 1);

        plugin.getDatabaseManager().canFileBugAsync(uuid, cooldownSec, maxOpen, maxDay, minHours)
                .thenAccept(gate -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (gate.blocked()) {
                        msg(sender, "<red>You are blocked from filing bugs"
                                + (gate.detail() != null ? ": " + safe(gate.detail()) : ".")
                                + "</red>");
                        return;
                    }
                    if (!gate.ok()) {
                        msg(sender, "<red>" + gate.detail());
                        return;
                    }
                    plugin.getDatabaseManager().findDuplicateOpenBugAsync(title).thenAccept(dup ->
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                if (dup != null) {
                                    msg(sender, "<yellow>That already looks like <gold>#" + dup.id
                                            + "</gold>: <white>" + safe(dup.title));
                                    msg(sender, "<gray>Use <yellow>/bug confirm " + dup.id
                                            + "</yellow> if it is the same issue, or write a more specific report.");
                                    return;
                                }
                                plugin.getDatabaseManager().createBugAsync(uuid, name, title, null)
                                        .thenAccept(id -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                                            if (id < 0) {
                                                msg(sender, "<red>Could not save that report. Try again.");
                                                return;
                                            }
                                            msg(sender, "<green>Filed bug <gold>#" + id + "</gold>.</green> "
                                                    + "<gray>Check /bug for status. If we need more, you'll see it there.");
                                            discord("**Bug #" + id + "** filed by **" + discordSafe(name) + "**\n"
                                                    + discordSafe(title));
                                        }));
                            }));
                }));
    }

    private void reply(CommandSender sender, String[] args) {
        UUID uuid = Actors.uuid(sender);
        if (uuid == null) {
            sender.sendMessage("Players only.");
            return;
        }
        if (args.length < 3) {
            msg(sender, "<red>Usage: /bug reply <id> <text>");
            return;
        }
        int id = parseId(args[1]);
        String text = sanitize(join(args, 2));
        if (id <= 0 || text.isBlank()) {
            msg(sender, "<red>Usage: /bug reply <id> <text>");
            return;
        }
        if (text.length() < 4) {
            msg(sender, "<red>Reply is too short.");
            return;
        }
        String name = Actors.name(sender);
        plugin.getDatabaseManager().replyBugAsync(id, uuid, name, text).thenAccept(err ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (err != null) {
                        msg(sender, "<red>" + err);
                        return;
                    }
                    msg(sender, "<green>Added to bug #" + id + ".");
                    discord("**Bug #" + id + "** reply from **" + discordSafe(name) + "**\n" + discordSafe(text));
                }));
    }

    private void confirm(CommandSender sender, String[] args) {
        UUID uuid = Actors.uuid(sender);
        if (uuid == null) {
            sender.sendMessage("Players only.");
            return;
        }
        if (args.length < 2) {
            msg(sender, "<red>Usage: /bug confirm <id>");
            return;
        }
        int id = parseId(args[1]);
        if (id <= 0) {
            msg(sender, "<red>Usage: /bug confirm <id>");
            return;
        }
        plugin.getDatabaseManager().isBugBlockedAsync(uuid).thenAccept(blockReason -> {
            if (blockReason != null) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        msg(sender, "<red>You are blocked from filing or confirming bugs."));
                return;
            }
            String name = Actors.name(sender);
            plugin.getDatabaseManager().confirmBugAsync(id, uuid, name).thenAccept(err ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (err != null) {
                            msg(sender, "<red>" + err);
                            return;
                        }
                        msg(sender, "<green>Noted on bug #" + id + ".");
                    }));
        });
    }

    private void close(CommandSender sender, String[] args) {
        if (!canModerate(sender)) {
            msg(sender, "<red>Only council members and ops can close bugs.");
            return;
        }
        if (args.length < 2) {
            msg(sender, "<red>Usage: /bug close <id> [false|spam|fixed|wontfix] [note]");
            return;
        }
        int id = parseId(args[1]);
        if (id <= 0) {
            msg(sender, "<red>Invalid id.");
            return;
        }
        String reason = "closed";
        int noteFrom = 2;
        if (args.length >= 3 && CLOSE_REASONS.contains(args[2].toLowerCase(Locale.ROOT))) {
            reason = args[2].toLowerCase(Locale.ROOT);
            noteFrom = 3;
        }
        String note = sanitize(join(args, noteFrom));
        if (note.isBlank()) note = reason;
        else note = reason + ": " + note;
        UUID actor = Actors.uuid(sender);
        String actorName = Actors.name(sender);
        boolean abuse = reason.equals("false") || reason.equals("spam") || reason.equals("invalid");
        int autoBlock = plugin.getConfig().getInt("bugs.auto-block-false-closes", 3);
        String noteFinal = note;
        String reasonFinal = reason;
        plugin.getDatabaseManager().closeBugAsync(id, actor, actorName, noteFinal, abuse, autoBlock)
                .thenAccept(result -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (result.error() != null) {
                        msg(sender, "<red>" + result.error());
                        return;
                    }
                    msg(sender, "<green>Closed bug #" + id + " (" + reasonFinal + ").");
                    discord("**Bug #" + id + " closed** by **" + discordSafe(actorName) + "** ("
                            + reasonFinal + ")\n" + discordSafe(noteFinal));
                    if (result.autoBlockedName() != null) {
                        msg(sender, "<yellow>" + result.autoBlockedName()
                                + " auto-blocked after repeated false reports.");
                        discord("**Bug reports:** **" + discordSafe(result.autoBlockedName())
                                + "** auto-blocked after repeated false/spam closes.");
                    }
                }));
    }

    private void reopen(CommandSender sender, String[] args) {
        if (!canModerate(sender)) {
            msg(sender, "<red>Only council members and ops can reopen bugs.");
            return;
        }
        if (args.length < 2) {
            msg(sender, "<red>Usage: /bug reopen <id>");
            return;
        }
        int id = parseId(args[1]);
        UUID actor = Actors.uuid(sender);
        String actorName = Actors.name(sender);
        plugin.getDatabaseManager().reopenBugAsync(id, actor, actorName).thenAccept(err ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (err != null) {
                        msg(sender, "<red>" + err);
                        return;
                    }
                    msg(sender, "<green>Reopened bug #" + id + ".");
                    discord("**Bug #" + id + " reopened** by **" + discordSafe(actorName) + "**");
                }));
    }

    private void ask(CommandSender sender, String[] args) {
        if (!canModerate(sender)) {
            msg(sender, "<red>Only council members and ops can ask for more info.");
            return;
        }
        if (args.length < 3) {
            msg(sender, "<red>Usage: /bug ask <id> <question>");
            return;
        }
        int id = parseId(args[1]);
        String q = sanitize(join(args, 2));
        if (id <= 0 || q.isBlank()) {
            msg(sender, "<red>Usage: /bug ask <id> <question>");
            return;
        }
        String actorName = Actors.name(sender);
        UUID actor = Actors.uuid(sender);
        plugin.getDatabaseManager().askBugAsync(id, actor, actorName, q).thenAccept(err ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (err != null) {
                        msg(sender, "<red>" + err);
                        return;
                    }
                    msg(sender, "<green>Asked on bug #" + id + ". Reporter will see it on join and in /bug.");
                    discord("**Bug #" + id + " needs info** (asked by **" + discordSafe(actorName) + "**)\n"
                            + discordSafe(q));
                }));
    }

    private void block(CommandSender sender, String[] args, boolean blocking) {
        if (!canModerate(sender)) {
            msg(sender, "<red>Only council members and ops can do that.");
            return;
        }
        if (args.length < 2) {
            msg(sender, "<red>Usage: /bug " + (blocking ? "block" : "unblock") + " <player> [reason]");
            return;
        }
        String targetName = args[1];
        String reason = blocking ? sanitize(join(args, 2)) : "";
        if (blocking && reason.isBlank()) reason = "false reports";
        UUID actor = Actors.uuid(sender);
        String actorName = Actors.name(sender);
        String reasonFinal = reason;
        plugin.getDatabaseManager().setBugBlockAsync(targetName, blocking, reasonFinal, actor, actorName)
                .thenAccept(err -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (err != null) {
                        msg(sender, "<red>" + err);
                        return;
                    }
                    msg(sender, blocking
                            ? "<yellow>Blocked " + safe(targetName) + " from filing bugs."
                            : "<green>Unblocked " + safe(targetName) + ".");
                    discord("**Bug reports:** **" + discordSafe(targetName) + "** "
                            + (blocking ? "blocked" : "unblocked") + " by **" + discordSafe(actorName) + "**"
                            + (blocking ? " (" + discordSafe(reasonFinal) + ")" : ""));
                }));
    }

    private void sendPending(CommandSender sender) {
        UUID uuid = Actors.uuid(sender);
        if (uuid == null) return;
        plugin.getDatabaseManager().listNeedsInfoForAsync(uuid).thenAccept(list ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    for (BugReport b : list) {
                        msg(sender, "<gold>Bug <yellow>#" + b.id + "</yellow> needs more info:</gold> <white>"
                                + safe(b.question != null ? b.question : b.title));
                        msg(sender, "<gray>Reply: <yellow>/bug reply " + b.id + " <answer>");
                    }
                }));
    }

    private boolean canModerate(CommandSender sender) {
        return sender.hasPermission("playercouncil.admin") || sender.hasPermission("playercouncil.council");
    }

    private void discord(String content) {
        plugin.getDiscordWebhook().send(content);
    }

    private void msg(CommandSender sender, String mini) {
        sender.sendMessage(MM.deserialize(mini));
    }

    private static int parseId(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String join(String[] args, int from) {
        if (args.length <= from) return "";
        return String.join(" ", Arrays.copyOfRange(args, from, args.length)).trim();
    }

    static String sanitize(String raw) {
        if (raw == null) return "";
        String s = raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').replace('§', ' ').trim();
        s = s.replaceAll("\\s+", " ");
        return stripCoords(s);
    }

    /** Never publish player coordinates (Discord, /bug, stored text). */
    static String stripCoords(String s) {
        return CoordRedact.apply(s);
    }

    static String safe(String s) {
        if (s == null) return "";
        return sanitize(s).replace('<', '‹').replace('>', '›');
    }

    static String discordSafe(String s) {
        if (s == null) return "";
        return sanitize(s)
                .replace("@everyone", "@\u200beveryone")
                .replace("@here", "@\u200bhere");
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }

    static String ago(long at) {
        long sec = Math.max(0, (System.currentTimeMillis() - at) / 1000L);
        if (sec < 60) return "just now";
        if (sec < 3600) return (sec / 60) + "m ago";
        if (sec < 86400) return (sec / 3600) + "h ago";
        long days = sec / 86400;
        return days + "d ago";
    }
}
