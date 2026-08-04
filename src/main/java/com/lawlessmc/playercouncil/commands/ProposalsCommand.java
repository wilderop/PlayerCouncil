package com.lawlessmc.playercouncil.commands;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import com.lawlessmc.playercouncil.models.Proposal;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class ProposalsCommand implements CommandExecutor {

    private final PlayerCouncilPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public ProposalsCommand(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        plugin.getProposalManager().getActiveProposals(active -> {
            sender.sendMessage(mm.deserialize("<gold>===== Active Proposals ====="));
            if (active.isEmpty()) {
                sender.sendMessage(mm.deserialize("<gray>No active proposals."));
                return;
            }
            for (Proposal p : active) {
                long remaining = p.getExpiresAt() - System.currentTimeMillis();
                String timeLeft = formatDuration(remaining);
                sender.sendMessage(mm.deserialize(
                        "<gold>#" + p.getId() + "</gold> <white>" + p.getDescription() +
                        " <gray>(" + timeLeft + " left)"));
                sender.sendMessage(mm.deserialize(
                        "  <green>Yes: " + p.getYesCount() + "</green> <red>No: " + p.getNoCount() + "</red>"));
                if (!p.getVotes().isEmpty()) {
                    StringBuilder voters = new StringBuilder("  Voters: ");
                    for (Map.Entry<UUID, Boolean> e : p.getVotes().entrySet()) {
                        String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
                        if (name == null) name = "?";
                        voters.append(e.getValue() ? "<green>" : "<red>")
                                .append(name)
                                .append("</").append(e.getValue() ? "green" : "red").append("> ");
                    }
                    sender.sendMessage(mm.deserialize(voters.toString()));
                }
            }
        });
        return true;
    }

    private String formatDuration(long ms) {
        if (ms <= 0) return "expired";
        long days = TimeUnit.MILLISECONDS.toDays(ms);
        long hours = TimeUnit.MILLISECONDS.toHours(ms) % 24;
        if (days > 0) return days + "d " + hours + "h";
        long mins = TimeUnit.MILLISECONDS.toMinutes(ms) % 60;
        return hours + "h " + mins + "m";
    }
}
