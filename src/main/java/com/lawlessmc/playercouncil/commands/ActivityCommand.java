package com.lawlessmc.playercouncil.commands;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class ActivityCommand implements CommandExecutor {

    private final PlayerCouncilPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public ActivityCommand(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String period = args.length > 0 ? args[0].toLowerCase() : "month";
        long since;
        String labelPeriod;
        if (period.equals("week") || period.equals("weekly")) {
            since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
            labelPeriod = "Last 7 days";
        } else {
            since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30);
            labelPeriod = "Last 30 days";
        }

        int minHours = plugin.getConfig().getInt("council.min-total-hours", 100);
        sender.sendMessage(mm.deserialize("<gray>Loading rankings..."));

        plugin.getActivityManager().getRankedPlayersAsync(since, minHours)
                .thenAccept(ranked -> Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(mm.deserialize("<gold>===== Activity Rankings (" + labelPeriod + ") ====="));
                    int limit = Math.min(20, ranked.size());
                    for (int i = 0; i < limit; i++) {
                        UUID uuid = ranked.get(i).getKey();
                        double score = ranked.get(i).getValue();
                        String name = Bukkit.getOfflinePlayer(uuid).getName();
                        if (name == null) name = uuid.toString().substring(0, 8);
                        sender.sendMessage(mm.deserialize(
                                "<yellow>" + (i + 1) + ".</yellow> <white>" + name +
                                " <gray>- score: <aqua>" + String.format("%.2f", score)));
                    }
                    if (ranked.isEmpty()) {
                        sender.sendMessage(mm.deserialize("<gray>No eligible players found."));
                    }
                }));
        return true;
    }
}
