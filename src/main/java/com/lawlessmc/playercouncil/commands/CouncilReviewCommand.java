package com.lawlessmc.playercouncil.commands;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CouncilReviewCommand implements CommandExecutor {

    private final PlayerCouncilPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public CouncilReviewCommand(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(mm.deserialize("<red>Usage: /councilreview <banId> reaffirm|overturn"));
            return true;
        }
        int banId;
        try {
            banId = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage(mm.deserialize("<red>Invalid ban id."));
            return true;
        }
        plugin.getBanReviewManager().handleResponse(player, banId, args[1]);
        return true;
    }
}
