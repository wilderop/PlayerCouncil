package com.lawlessmc.playercouncil.commands;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public class CouncilCommand implements CommandExecutor {

    private final PlayerCouncilPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public CouncilCommand(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        List<UUID> members = plugin.getCouncilManager().getCouncilMembers();
        int minActive = plugin.getCouncilManager().getMinActiveMembers();
        boolean active = plugin.getCouncilManager().isSystemActive();

        sender.sendMessage(mm.deserialize("<gold>===== Player Council ====="));
        sender.sendMessage(mm.deserialize(
                active
                        ? "<green>Status: ACTIVE</green> <gray>(" + members.size() + "/" + minActive + " minimum met)"
                        : "<red>Status: INACTIVE</red> <gray>(need " + minActive + " members, currently " + members.size() + ")"));

        if (members.isEmpty()) {
            sender.sendMessage(mm.deserialize("<gray>No council members yet."));
            return true;
        }
        int i = 1;
        for (UUID uuid : members) {
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            if (name == null) name = uuid.toString().substring(0, 8);
            sender.sendMessage(mm.deserialize("<yellow>" + i++ + ".</yellow> <white>" + name));
        }
        if (sender instanceof Player p) {
            boolean isMember = plugin.getCouncilManager().isCouncilMember(p.getUniqueId());
            sender.sendMessage(mm.deserialize(isMember
                    ? "<green>You are a council member."
                    : "<gray>You are not currently a council member."));
        }
        return true;
    }
}
