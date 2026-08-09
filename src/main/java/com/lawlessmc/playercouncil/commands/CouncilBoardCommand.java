package com.lawlessmc.playercouncil.commands;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class CouncilBoardCommand implements CommandExecutor {

    private final PlayerCouncilPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public CouncilBoardCommand(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!plugin.getDisplayManager().isFeatureEnabled()) {
            player.sendMessage(mm.deserialize(
                    "<red>Activity scoreboard is disabled on this server "
                            + "(set <yellow>display.enabled: true</yellow> in config to allow it)."));
            return true;
        }
        var result = plugin.getDisplayManager().toggle(player);
        if (result.isEmpty()) {
            player.sendMessage(mm.deserialize("<red>Scoreboard feature is disabled."));
            return true;
        }
        if (result.get()) {
            player.sendMessage(mm.deserialize(
                    "<green>Activity scoreboard <white>ON</white>. "
                            + "It refreshes automatically; use /councilboard again to turn off."));
        } else {
            player.sendMessage(mm.deserialize("<yellow>Activity scoreboard <white>OFF</white>."));
        }
        return true;
    }
}
