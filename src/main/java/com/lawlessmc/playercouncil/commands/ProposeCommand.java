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

    private record Pending(Proposal.Type type, String target, String value) {}

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
            plugin.getProposalManager().createProposal(player, p.type, p.target, p.value);
            return true;
        }

        if (args[0].equalsIgnoreCase("cancel")) {
            pending.remove(player.getUniqueId());
            player.sendMessage(mm.deserialize("<yellow>Pending proposal cancelled."));
            return true;
        }

        String action = args[0].toLowerCase();

        if (action.equals("ban") || action.equals("unban") || action.equals("pardon")) {
            if (args.length < 2) {
                player.sendMessage(mm.deserialize("<red>Usage: /propose ban <player>  or  /propose unban <player>"));
                return true;
            }
            boolean wantBan = action.equals("ban");
            String targetName = args[1];
            player.sendMessage(mm.deserialize("<gray>Resolving ban ladder for <white>" + targetName + "</white>..."));

            plugin.getBanVoteManager().resolveLadder(targetName, wantBan).thenAccept(res ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (res.type() == null) {
                            player.sendMessage(mm.deserialize("<red>" + res.explanation()));
                            return;
                        }
                        pending.put(player.getUniqueId(), new Pending(res.type(), res.targetName(), null));
                        player.sendMessage(mm.deserialize("<gold>Confirm proposal:</gold>"));
                        player.sendMessage(mm.deserialize("  <white>" + res.type().name() + " → " + res.targetName()));
                        player.sendMessage(mm.deserialize("  <gray>" + res.explanation()));
                        player.sendMessage(mm.deserialize("  <yellow>Needs <white>" + res.requiredVotes()
                                + "</white> yes votes to pass."));
                        player.sendMessage(mm.deserialize(
                                "<yellow>Type <white>/propose confirm</white> to submit, or <white>/propose cancel</white> to abort."));
                    }));
            return true;
        }

        Proposal.Type type;
        try {
            type = Proposal.Type.valueOf(args[0].toUpperCase());
        } catch (IllegalArgumentException e) {
            player.sendMessage(mm.deserialize("<red>Unknown type. Prefer: ban, unban, GAMERULE, PLUGIN_ENABLE, PLUGIN_DISABLE"));
            return true;
        }

        if (type == Proposal.Type.BAN || type == Proposal.Type.REBAN
                || type == Proposal.Type.PARDON || type == Proposal.Type.REPARDON) {
            if (args.length < 2) {
                player.sendMessage(mm.deserialize("<red>Usage: /propose ban|unban <player>"));
                return true;
            }
            boolean wantBan = (type == Proposal.Type.BAN || type == Proposal.Type.REBAN);
            plugin.getBanVoteManager().resolveLadder(args[1], wantBan).thenAccept(res ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (res.type() == null) {
                            player.sendMessage(mm.deserialize("<red>" + res.explanation()));
                            return;
                        }
                        pending.put(player.getUniqueId(), new Pending(res.type(), res.targetName(), null));
                        player.sendMessage(mm.deserialize("<gold>Confirm (auto ladder):</gold> <white>"
                                + res.type().name() + " → " + res.targetName()));
                        player.sendMessage(mm.deserialize("<gray>" + res.explanation()));
                        player.sendMessage(mm.deserialize(
                                "<yellow>/propose confirm</white> or <white>/propose cancel"));
                    }));
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
                target = args[1];
                value = args[2];
                if (!plugin.getProposalManager().isValidGameRule(target)) {
                    player.sendMessage(mm.deserialize("<red>Invalid gamerule name: " + target));
                    return true;
                }
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
            default -> {
                player.sendMessage(mm.deserialize("<red>Unsupported type."));
                return true;
            }
        }

        pending.put(player.getUniqueId(), new Pending(type, target, value));
        player.sendMessage(mm.deserialize("<gold>Confirm proposal:</gold> <white>" + type.name()
                + " → " + target + (value != null ? " = " + value : "")));
        player.sendMessage(mm.deserialize(
                "<yellow>Type <white>/propose confirm</white> to submit, or <white>/propose cancel</white> to abort."));
        return true;
    }

    private void sendUsage(Player player) {
        player.sendMessage(mm.deserialize("<gold>Proposal types:</gold>"));
        player.sendMessage(mm.deserialize("  <yellow>/propose ban <player></yellow> <gray>— auto ladder (1 or 4 votes)"));
        player.sendMessage(mm.deserialize("  <yellow>/propose unban <player></yellow> <gray>— auto ladder (2 or 8 votes)"));
        player.sendMessage(mm.deserialize("  <yellow>/propose GAMERULE <rule> <value></yellow> <gray>— all worlds"));
        player.sendMessage(mm.deserialize("  <yellow>/propose PLUGIN_ENABLE <plugin>"));
        player.sendMessage(mm.deserialize("  <yellow>/propose PLUGIN_DISABLE <plugin>"));
    }
}
