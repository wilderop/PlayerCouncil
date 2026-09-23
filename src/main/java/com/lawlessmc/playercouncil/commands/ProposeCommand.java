package com.lawlessmc.playercouncil.commands;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import com.lawlessmc.playercouncil.models.Proposal;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ProposeCommand implements CommandExecutor {

    private final PlayerCouncilPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, Pending> pending = new HashMap<>();

    public ProposeCommand(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    private record Pending(Proposal.Type type, String target, String value, String reason) {}

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        UUID actor = com.lawlessmc.playercouncil.bridge.Actors.uuid(sender);
        if (actor == null) {
            sender.sendMessage("Players only.");
            return true;
        }
        String actorName = com.lawlessmc.playercouncil.bridge.Actors.name(sender);
        if (!plugin.getCouncilManager().isCouncilMember(actor)) {
            sender.sendMessage(mm.deserialize("<red>Only council members can create proposals."));
            return true;
        }
        if (!plugin.getCouncilManager().isSystemActive()) {
            int need = plugin.getCouncilManager().getMinActiveMembers();
            int have = plugin.getCouncilManager().getCouncilMembers().size();
            sender.sendMessage(mm.deserialize(
                    "<red>Council voting is not active yet. Need at least <yellow>" + need +
                    "</yellow> members (currently <yellow>" + have + "</yellow>)."));
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("confirm")) {
            Pending p = pending.remove(actor);
            if (p == null) {
                sender.sendMessage(mm.deserialize("<red>No pending proposal to confirm."));
                return true;
            }
            if (p.type() == Proposal.Type.BAN || p.type() == Proposal.Type.REBAN) {
                plugin.getDatabaseManager().getBanProposeCooldownAsync(actor).thenAccept(until ->
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (until > System.currentTimeMillis()) {
                                long leftH = (until - System.currentTimeMillis()) / (1000L * 60L * 60L);
                                sender.sendMessage(mm.deserialize(
                                        "<red>You cannot propose bans for another <yellow>" + leftH
                                                + "</yellow> hour(s) (cooldown after your ban was overturned)."));
                                return;
                            }
                            plugin.getProposalManager().createProposal(sender, actor, actorName, p.type(), p.target(), p.value(), p.reason());
                        }));
                return true;
            }
            plugin.getProposalManager().createProposal(sender, actor, actorName, p.type(), p.target(), p.value(), p.reason());
            return true;
        }

        if (args[0].equalsIgnoreCase("cancel")) {
            pending.remove(actor);
            sender.sendMessage(mm.deserialize("<yellow>Pending proposal cancelled."));
            return true;
        }

        String action = args[0].toLowerCase();

        // --- Suggestion (advisory text for admin) ---
        if (action.equals("suggestion") || action.equals("suggest")) {
            if (args.length < 2) {
                sender.sendMessage(mm.deserialize(
                        "<red>Usage: /propose suggestion <text up to 256 characters>"));
                return true;
            }
            String text = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
            if (text.isEmpty()) {
                sender.sendMessage(mm.deserialize("<red>Suggestion text cannot be empty."));
                return true;
            }
            if (text.length() > 256) {
                sender.sendMessage(mm.deserialize(
                        "<red>Suggestion too long (<yellow>" + text.length()
                                + "</yellow>/256). Shorten it and try again."));
                return true;
            }
            pending.put(actor, new Pending(Proposal.Type.SUGGESTION, text, null, null));
            sender.sendMessage(mm.deserialize("<gold>Confirm proposal:</gold> <white>SUGGESTION → " + text));
            sender.sendMessage(mm.deserialize(
                    "<gray>Advisory only — if it passes, it is recorded for the server admin (no auto action)."));
            sender.sendMessage(mm.deserialize(
                    "<yellow>Type <white>/propose confirm</white> to submit, or <white>/propose cancel</white> to abort."));
            return true;
        }

        // --- Automatic ban / unban ladder ---
        if (action.equals("ban") || action.equals("unban") || action.equals("pardon")) {
            if (args.length < 2) {
                sender.sendMessage(mm.deserialize(
                        "<red>Usage: /propose ban <player> [reason...]  or  /propose unban <player> [reason...]"));
                return true;
            }
            boolean wantBan = action.equals("ban");
            String targetName = args[1];
            String reason = args.length > 2
                    ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim()
                    : null;
            if (reason != null && reason.isEmpty()) reason = null;
            if (reason != null && reason.length() > 200) {
                sender.sendMessage(mm.deserialize("<red>Reason too long (max 200 characters)."));
                return true;
            }

            if (wantBan) {
                final String reasonFinal = reason;
                plugin.getDatabaseManager().getBanProposeCooldownAsync(actor).thenAccept(until ->
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (until > System.currentTimeMillis()) {
                                long leftMs = until - System.currentTimeMillis();
                                long leftH = leftMs / (1000L * 60L * 60L);
                                sender.sendMessage(mm.deserialize(
                                        "<red>You cannot propose bans for another <yellow>" + leftH
                                                + "</yellow> hour(s) (cooldown after your ban was overturned)."));
                                return;
                            }
                            startBanLadder(sender, actor, targetName, true, reasonFinal);
                        }));
                return true;
            }

            startBanLadder(sender, actor, targetName, false, reason);
            return true;
        }

        // --- Legacy explicit types still allowed (REBAN etc.) but ban/unban preferred ---
        Proposal.Type type;
        try {
            type = Proposal.Type.valueOf(args[0].toUpperCase());
        } catch (IllegalArgumentException e) {
            sender.sendMessage(mm.deserialize(
                    "<red>Unknown type. Prefer: ban, unban, suggestion, GAMERULE, PLUGIN_ENABLE, PLUGIN_DISABLE"));
            return true;
        }

        if (type == Proposal.Type.BAN || type == Proposal.Type.REBAN
                || type == Proposal.Type.PARDON || type == Proposal.Type.REPARDON) {
            if (args.length < 2) {
                sender.sendMessage(mm.deserialize("<red>Usage: /propose ban|unban <player>"));
                return true;
            }
            boolean wantBan = (type == Proposal.Type.BAN || type == Proposal.Type.REBAN);
            if (wantBan) {
                plugin.getDatabaseManager().getBanProposeCooldownAsync(actor).thenAccept(until ->
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (until > System.currentTimeMillis()) {
                                long leftH = (until - System.currentTimeMillis()) / (1000L * 60L * 60L);
                                sender.sendMessage(mm.deserialize(
                                        "<red>You cannot propose bans for another <yellow>" + leftH
                                                + "</yellow> hour(s)."));
                                return;
                            }
                            String legReason = args.length > 2
                                    ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim()
                                    : null;
                            startBanLadder(sender, actor, args[1], true, legReason);
                        }));
                return true;
            }
            String legReason = args.length > 2
                    ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim()
                    : null;
            startBanLadder(sender, actor, args[1], false, legReason);
            return true;
        }

        String target;
        String value = null;

        switch (type) {
            case GAMERULE -> {
                if (args.length < 3) {
                    sender.sendMessage(mm.deserialize("<red>Usage: /propose GAMERULE <rule> <value>"));
                    return true;
                }
                String rawRule = args[1];
                value = args[2];
                String resolved = plugin.getProposalManager().resolveGameruleInput(rawRule);
                if (resolved == null) {
                    sender.sendMessage(mm.deserialize("<red>Invalid gamerule name: <white>" + rawRule));
                    String hint = plugin.getProposalManager().findClosestGamerule(rawRule);
                    if (hint != null) {
                        sender.sendMessage(mm.deserialize("<gray>Did you mean <yellow>" + hint + "</yellow>?"));
                    } else {
                        sender.sendMessage(mm.deserialize("<gray>Example: <yellow>/propose GAMERULE spawn_phantoms false"));
                    }
                    return true;
                }
                if (!resolved.equalsIgnoreCase(rawRule) && !normalizeLoose(rawRule).equals(normalizeLoose(resolved))) {
                    sender.sendMessage(mm.deserialize("<gray>Interpreted gamerule <white>" + rawRule
                            + "</white> as <yellow>" + resolved + "</yellow>."));
                }
                target = resolved;
            }
            case PLUGIN_ENABLE, PLUGIN_DISABLE -> {
                if (args.length < 2) {
                    sender.sendMessage(mm.deserialize("<red>Usage: /propose " + type.name() + " <plugin>"));
                    return true;
                }
                target = args[1];
                if (!plugin.getProposalManager().isWhitelistedPlugin(target)) {
                    sender.sendMessage(mm.deserialize("<red>Plugin is not on the council whitelist: " + target));
                    return true;
                }
            }
            case SUGGESTION -> {
                if (args.length < 2) {
                    sender.sendMessage(mm.deserialize(
                            "<red>Usage: /propose SUGGESTION <text up to 256 characters>"));
                    return true;
                }
                target = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
                if (target.isEmpty()) {
                    sender.sendMessage(mm.deserialize("<red>Suggestion text cannot be empty."));
                    return true;
                }
                if (target.length() > 256) {
                    sender.sendMessage(mm.deserialize(
                            "<red>Suggestion too long (<yellow>" + target.length()
                                    + "</yellow>/256). Shorten it and try again."));
                    return true;
                }
            }
            default -> {
                sender.sendMessage(mm.deserialize("<red>Unsupported type."));
                return true;
            }
        }

        pending.put(actor, new Pending(type, target, value, null));
        sender.sendMessage(mm.deserialize("<gold>Confirm proposal:</gold> <white>" + type.name()
                + " → " + target + (value != null ? " = " + value : "")));
        sender.sendMessage(mm.deserialize(
                "<yellow>Type <white>/propose confirm</white> to submit, or <white>/propose cancel</white> to abort."));
        return true;
    }

    private static String normalizeLoose(String s) {
        return s == null ? "" : s.trim().toLowerCase().replace("_", "").replace("-", "");
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(mm.deserialize("<gold>Proposal types:</gold>"));
        sender.sendMessage(mm.deserialize("  <yellow>/propose ban <player> [reason...]</yellow> <gray>— auto ladder"));
        sender.sendMessage(mm.deserialize("  <yellow>/propose unban <player> [reason...]</yellow> <gray>— auto ladder"));
        sender.sendMessage(mm.deserialize("  <yellow>/propose suggestion <text></yellow> <gray>— advisory (max 256 chars)"));
        sender.sendMessage(mm.deserialize("  <yellow>/propose GAMERULE <rule> <value></yellow> <gray>— all worlds"));
        sender.sendMessage(mm.deserialize("  <yellow>/propose PLUGIN_ENABLE <plugin>"));
        sender.sendMessage(mm.deserialize("  <yellow>/propose PLUGIN_DISABLE <plugin>"));
    }

    private void startBanLadder(CommandSender sender, UUID actor, String targetName, boolean wantBan, String reason) {
        sender.sendMessage(mm.deserialize("<gray>Resolving ban ladder for <white>" + targetName + "</white>..."));
        plugin.getBanVoteManager().resolveLadder(targetName, wantBan).thenAccept(res ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (res.type() == null) {
                        sender.sendMessage(mm.deserialize("<red>" + res.explanation()));
                        return;
                    }
                    pending.put(actor, new Pending(
                            res.type(), res.targetName(), String.valueOf(res.requiredVotes()), reason));
                    sender.sendMessage(mm.deserialize("<gold>Confirm proposal:</gold>"));
                    sender.sendMessage(mm.deserialize("  <white>" + res.type().name() + " → " + res.targetName()));
                    sender.sendMessage(mm.deserialize("  <gray>" + res.explanation()));
                    if (reason != null && !reason.isBlank()) {
                        sender.sendMessage(mm.deserialize("  <gray>Reason: <white>" + reason));
                    }
                    sender.sendMessage(mm.deserialize("  <yellow>Needs <white>" + res.requiredVotes()
                            + "</white> yes votes to pass."));
                    sender.sendMessage(mm.deserialize(
                            "<yellow>Type <white>/propose confirm</white> to submit, or <white>/propose cancel</white> to abort."));
                }));
    }
}
