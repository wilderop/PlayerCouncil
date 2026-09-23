package com.lawlessmc.playercouncil.commands;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class CouncilCommand implements CommandExecutor, TabCompleter {

    private final PlayerCouncilPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public CouncilCommand(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        UUID self = com.lawlessmc.playercouncil.bridge.Actors.uuid(sender);
        boolean op = sender.isOp();
        boolean council = self != null && plugin.getCouncilManager().isCouncilMember(self);
        if (args.length >= 1 && (args[0].equalsIgnoreCase("link") || args[0].equalsIgnoreCase("unlink"))) {
            if (self == null) {
                sender.sendMessage("Players only.");
                return true;
            }
            if (!op && !council) {
                sender.sendMessage(mm.deserialize("<red>Unknown command. Type \"/help\" for help."));
                return true;
            }
            if (args[0].equalsIgnoreCase("unlink")) {
                plugin.getDiscordLinkManager().handleUnlink(sender, self);
            } else {
                plugin.getDiscordLinkManager().handleLink(sender, self,
                        com.lawlessmc.playercouncil.bridge.Actors.name(sender), op, args);
            }
            return true;
        }

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
        if (self != null) {
            sender.sendMessage(mm.deserialize(council
                    ? "<green>You are a council member."
                    : "<gray>You are not currently a council member."));
            if (council || op) {
                plugin.getDiscordLinkManager().sendUnlinkedHint(sender, self);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        UUID self = com.lawlessmc.playercouncil.bridge.Actors.uuid(sender);
        boolean allowed = sender.isOp()
                || (self != null && plugin.getCouncilManager().isCouncilMember(self));
        if (!allowed) return List.of();
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            return List.of("link", "unlink").stream().filter(s -> s.startsWith(p)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("link")) {
            String p = args[1].toLowerCase(Locale.ROOT);
            return List.of("status").stream().filter(s -> s.startsWith(p)).toList();
        }
        return List.of();
    }
}
