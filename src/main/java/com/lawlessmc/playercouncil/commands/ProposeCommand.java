package com.lawlessmc.playercouncil.commands;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import com.lawlessmc.playercouncil.managers.BanVoteManager;
import com.lawlessmc.playercouncil.models.Proposal;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

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
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!plugin.getCouncilManager().isCouncilMember(player.getUniqueId())) {
            player.sendMessage(mm.deserialize("<red>Only council members can create proposals."));
            return true;
        }
        if (!plugin.getCouncilManager().isSystemActive()) {
            int need = plugin.getCouncilManager().getMinActiveMembers();
            int have = plugin.getCouncilManager().getCouncilMembers().size();
            player.sendMessage(mm.deserialize(
                    "<red>Council voting is not active yet. Need at least <yellow>" + need +
                    "</yellow> members (currently <yellow>" + have + "</yellow>)."));
            return true;
        }

        if (args.length == 0) {
            sendUsage(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("confirm")) {
            Pending p = pending.remove(player.getUniqueId());
            if (p == null) {
                player.sendMessage(mm.deserialize("<red>No pending proposal to confirm."));
                return true;
            }
            if (p.type() == Proposal.Type.BAN || p.type() == Proposal.Type.REBAN) {
                plugin.getDatabaseManager().getBanProposeCooldownAsync(player.getUniqueId()).thenAccept(until ->
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (until > System.currentTimeMillis()) {
                                long leftH = (until - System.currentTimeMillis()) / (1000L * 60L * 60L);
                                player.sendMessage(mm.deserialize(
                                        "<red>You cannot propose bans for another <yellow>" + leftH
                                                + "</yellow> hour(s) (cooldown after your ban was overturned)."));
                                return;
                            }
                            plugin.getProposalManager().createProposal(player, p.type(), p.target(), p.value(), p.reason());
                        }));
                return true;
            }
            plugin.getProposalManager().createProposal(player, p.type(), p.target(), p.value(), p.reason());
            return true;
        }

        if (args[0].equalsIgnoreCase("cancel")) {
            pending.remove(player.getUniqueId());
            player.sendMessage(mm.deserialize("<yellow>Pending proposal cancelled."));
            return true;
        }

        String action = args[0].toLowerCase();

        // --- Suggestion (advisory text for admin) ---
        if (action.equals("suggestion") || action.equals("suggest")) {
            if (args.length < 2) {
                player.sendMessage(mm.deserialize(
                        "<red>Usage: /propose suggestion <text up to 256 characters>"));
                return true;
            }
            String text = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
            if (text.isEmpty()) {
                player.sendMessage(mm.deserialize("<red>Suggestion text cannot be empty."));
                return true;
            }
            if (text.length() > 256) {
                player.sendMessage(mm.deserialize(
                        "<red>Suggestion too long (<yellow>" + text.length()
                                + "</yellow>/256). Shorten it and try again."));
                return true;
            }
            pending.put(player.getUniqueId(), new Pending(Proposal.Type.SUGGESTION, text, null, null));
            player.sendMessage(mm.deserialize("<gold>Confirm proposal:</gold> <white>SUGGESTION → " + text));
            player.sendMessage(mm.deserialize(
                    "<gray>Advisory only — if it passes, it is recorded for the server admin (no auto action)."));
            player.sendMessage(mm.deserialize(
                    "<yellow>Type <white>/propose confirm</white> to submit, or <white>/propose cancel</white> to abort."));
            return true;
        }

        // --- Automatic ban / unban ladder ---
        if (action.equals("ban") || action.equals("unban") || action.equals("pardon")) {
            if (args.length < 2) {
                player.sendMessage(mm.deserialize(
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
                player.sendMessage(mm.deserialize("<red>Reason too long (max 200 characters)."));
                return true;
            }

            if (wantBan) {
                final String reasonFinal = reason;
                plugin.getDatabaseManager().getBanProposeCooldownAsync(player.getUniqueId()).thenAccept(until ->
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (until > System.currentTimeMillis()) {
                                long leftMs = until - System.currentTimeMillis();
                                long leftH = leftMs / (1000L * 60L * 60L);
                                player.sendMessage(mm.deserialize(
                                        "<red>You cannot propose bans for another <yellow>" + leftH
                                                + "</yellow> hour(s) (cooldown after your ban was overturned)."));
                                return;
                            }
                            startBanLadder(player, targetName, true, reasonFinal);
                        }));
                return true;
            }

            startBanLadder(player, targetName, false, reason);
            return true;
        }

        // --- Legacy explicit types still allowed (REBAN etc.) but ban/unban preferred ---
        Proposal.Type type;
        try {
            type = Proposal.Type.valueOf(args[0].toUpperCase());
        } catch (IllegalArgumentException e) {
            player.sendMessage(mm.deserialize(
                    "<red>Unknown type. Prefer: ban, unban, suggestion, GAMERULE, PLUGIN_ENABLE, PLUGIN_DISABLE"));
            return true;
        }

        if (type == Proposal.Type.BAN || type == Proposal.Type.REBAN
                || type == Proposal.Type.PARDON || type == Proposal.Type.REPARDON) {
            if (args.length < 2) {
                player.sendMessage(mm.deserialize("<red>Usage: /propose ban|unban <player>"));
                return true;
            }
            boolean wantBan = (type == Proposal.Type.BAN || type == Proposal.Type.REBAN);
            if (wantBan) {
                plugin.getDatabaseManager().getBanProposeCooldownAsync(player.getUniqueId()).thenAccept(until ->
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (until > System.currentTimeMillis()) {
                                long leftH = (until - System.currentTimeMillis()) / (1000L * 60L * 60L);
                                player.sendMessage(mm.deserialize(
                                        "<red>You cannot propose bans for another <yellow>" + leftH
                                                + "</yellow> hour(s)."));
                                return;
                            }
                            String legReason = args.length > 2
                                    ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim()
                                    : null;
                            startBanLadder(player, args[1], true, legReason);
                        }));
                return true;
            }
            String legReason = args.length > 2
                    ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim()
                    : null;
            startBanLadder(player, args[1], false, legReason);
            return true;
        }

        String target;
        String value = null;

        switch (type) {
            case GAMERULE -> {
                if (args.length < 3) {
                    player.sendMessage(mm.deserialize("<red>Usage: /propose GAMERULE <rule> <value>"));
                    return true;
                }
                String rawRule = args[1];
                value = args[2];
                String resolved = plugin.getProposalManager().resolveGameruleInput(rawRule);
                if (resolved == null) {
                    player.sendMessage(mm.deserialize("<red>Invalid gamerule name: <white>" + rawRule));
                    String hint = plugin.getProposalManager().findClosestGamerule(rawRule);
                    if (hint != null) {
                        player.sendMessage(mm.deserialize("<gray>Did you mean <yellow>" + hint + "</yellow>?"));
                    } else {
                        player.sendMessage(mm.deserialize("<gray>Example: <yellow>/propose GAMERULE spawn_phantoms false"));
                    }
                    return true;
                }
                if (!resolved.equalsIgnoreCase(rawRule) && !normalizeLoose(rawRule).equals(normalizeLoose(resolved))) {
                    player.sendMessage(mm.deserialize("<gray>Interpreted gamerule <white>" + rawRule
                            + "</white> as <yellow>" + resolved + "</yellow>."));
                }
                target = resolved;
            }
            case PLUGIN_ENABLE, PLUGIN_DISABLE -> {
                if (args.length < 2) {
                    player.sendMessage(mm.deserialize("<red>Usage: /propose " + type.name() + " <plugin>"));
                    return true;
                }
                target = args[1];
                if (!plugin.getProposalManager().isWhitelistedPlugin(target)) {
                    player.sendMessage(mm.deserialize("<red>Plugin is not on the council whitelist: " + target));
                    return true;
                }
            }
            case SUGGESTION -> {
                if (args.length < 2) {
                    player.sendMessage(mm.deserialize(
                            "<red>Usage: /propose SUGGESTION <text up to 256 characters>"));
                    return true;
                }
                target = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
                if (target.isEmpty()) {
                    player.sendMessage(mm.deserialize("<red>Suggestion text cannot be empty."));
                    return true;
                }
                if (target.length() > 256) {
                    player.sendMessage(mm.deserialize(
                            "<red>Suggestion too long (<yellow>" + target.length()
                                    + "</yellow>/256). Shorten it and try again."));
                    return true;
                }
            }
            default -> {
                player.sendMessage(mm.deserialize("<red>Unsupported type."));
                return true;
            }
        }

        pending.put(player.getUniqueId(), new Pending(type, target, value, null));
        player.sendMessage(mm.deserialize("<gold>Confirm proposal:</gold> <white>" + type.name()
                + " → " + target + (value != null ? " = " + value : "")));
        player.sendMessage(mm.deserialize(
                "<yellow>Type <white>/propose confirm</white> to submit, or <white>/propose cancel</white> to abort."));
        return true;
    }

    private static String normalizeLoose(String s) {
        return s == null ? "" : s.trim().toLowerCase().replace("_", "").replace("-", "");
    }

    private void sendUsage(Player player) {
        player.sendMessage(mm.deserialize("<gold>Proposal types:</gold>"));
        player.sendMessage(mm.deserialize("  <yellow>/propose ban <player> [reason...]</yellow> <gray>— auto ladder"));
        player.sendMessage(mm.deserialize("  <yellow>/propose unban <player> [reason...]</yellow> <gray>— auto ladder"));
        player.sendMessage(mm.deserialize("  <yellow>/propose suggestion <text></yellow> <gray>— advisory (max 256 chars)"));
        player.sendMessage(mm.deserialize("  <yellow>/propose GAMERULE <rule> <value></yellow> <gray>— all worlds"));
        player.sendMessage(mm.deserialize("  <yellow>/propose PLUGIN_ENABLE <plugin>"));
        player.sendMessage(mm.deserialize("  <yellow>/propose PLUGIN_DISABLE <plugin>"));
    }

    private void startBanLadder(Player player, String targetName, boolean wantBan, String reason) {
        player.sendMessage(mm.deserialize("<gray>Resolving ban ladder for <white>" + targetName + "</white>..."));
        plugin.getBanVoteManager().resolveLadder(targetName, wantBan).thenAccept(res ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (res.type() == null) {
                        player.sendMessage(mm.deserialize("<red>" + res.explanation()));
                        return;
                    }
                    pending.put(player.getUniqueId(), new Pending(
                            res.type(), res.targetName(), String.valueOf(res.requiredVotes()), reason));
                    player.sendMessage(mm.deserialize("<gold>Confirm proposal:</gold>"));
                    player.sendMessage(mm.deserialize("  <white>" + res.type().name() + " → " + res.targetName()));
                    player.sendMessage(mm.deserialize("  <gray>" + res.explanation()));
                    if (reason != null && !reason.isBlank()) {
                        player.sendMessage(mm.deserialize("  <gray>Reason: <white>" + reason));
                    }
                    player.sendMessage(mm.deserialize("  <yellow>Needs <white>" + res.requiredVotes()
                            + "</white> yes votes to pass."));
                    player.sendMessage(mm.deserialize(
                            "<yellow>Type <white>/propose confirm</white> to submit, or <white>/propose cancel</white> to abort."));
                }));
    }
}
