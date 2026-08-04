package com.lawlessmc.playercouncil.commands;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class VoteCommand implements CommandExecutor {

    private final PlayerCouncilPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public VoteCommand(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!plugin.getCouncilManager().isCouncilMember(player.getUniqueId())) {
            player.sendMessage(mm.deserialize("<red>Only council members can vote."));
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
        if (args.length < 2) {
            player.sendMessage(mm.deserialize("<red>Usage: /councilvote <id> <yes|no>"));
            return true;
        }

        int id;
        try {
            id = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage(mm.deserialize("<red>Invalid proposal id."));
            return true;
        }

        boolean yes;
        String v = args[1].toLowerCase();
        if (v.equals("yes") || v.equals("y") || v.equals("true") || v.equals("1")) {
            yes = true;
        } else if (v.equals("no") || v.equals("n") || v.equals("false") || v.equals("0")) {
            yes = false;
        } else {
            player.sendMessage(mm.deserialize("<red>Vote must be yes or no."));
            return true;
        }

        plugin.getProposalManager().vote(player, id, yes);
        return true;
    }
}
