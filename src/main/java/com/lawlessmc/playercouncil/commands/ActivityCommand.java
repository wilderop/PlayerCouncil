package com.lawlessmc.playercouncil.commands;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import com.lawlessmc.playercouncil.util.TrackedStats;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

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
        if (args.length >= 1 && isDetailRequest(args[0])) {
            String who = args[0];
            String periodArg = args.length >= 2 ? args[1] : "month";
            Period period = parsePeriod(periodArg);

            UUID targetUuid;
            String displayName;
            if (who.equalsIgnoreCase("me")) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Players only for /activity me.");
                    return true;
                }
                targetUuid = player.getUniqueId();
                displayName = player.getName();
            } else {
                OfflinePlayer off = resolveOffline(who);
                if (off == null || (!off.hasPlayedBefore() && !off.isOnline())) {
                    sender.sendMessage(mm.deserialize("<red>Player not found: <white>" + who));
                    return true;
                }
                targetUuid = off.getUniqueId();
                displayName = off.getName() != null ? off.getName() : who;
            }

            sender.sendMessage(mm.deserialize("<gray>Loading activity for <white>" + displayName + "</white>..."));
            plugin.getActivityManager().getPlayerDetailAsync(targetUuid, period.sinceMs())
                    .thenAccept(detail -> Bukkit.getScheduler().runTask(plugin, () ->
                            sendDetail(sender, displayName, period.label(), detail)));
            return true;
        }

        Period period = parsePeriod(args.length > 0 ? args[0] : "month");
        int minHours = plugin.getConfig().getInt("council.min-total-hours", 100);
        sender.sendMessage(mm.deserialize("<gray>Loading rankings..."));

        plugin.getActivityManager().getRankedPlayersAsync(period.sinceMs(), minHours)
                .thenAccept(ranked -> Bukkit.getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(mm.deserialize(
                            "<gold>===== Activity Rankings (" + period.label() + ") ====="));
                    int limit = Math.min(20, ranked.size());
                    for (int i = 0; i < limit; i++) {
                        UUID uuid = ranked.get(i).getKey();
                        double score = ranked.get(i).getValue();
                        String name = Bukkit.getOfflinePlayer(uuid).getName();
                        if (name == null) name = uuid.toString().substring(0, 8);
                        boolean council = plugin.getCouncilManager().isCouncilMember(uuid);
                        String star = council ? " <gold>★</gold>" : "";
                        sender.sendMessage(mm.deserialize(
                                "<yellow>" + (i + 1) + ".</yellow> <white>" + name + star +
                                        " <gray>- score: <aqua>" + String.format("%.2f", score)));
                    }
                    if (ranked.isEmpty()) {
                        sender.sendMessage(mm.deserialize("<gray>No eligible players found."));
                    }
                    sender.sendMessage(mm.deserialize(
                            "<gray>Tip: <white>/activity me</white> for your per-stat breakdown."));
                }));
        return true;
    }

    private static boolean isDetailRequest(String arg) {
        if (arg.equalsIgnoreCase("week") || arg.equalsIgnoreCase("weekly")
                || arg.equalsIgnoreCase("month") || arg.equalsIgnoreCase("monthly")) {
            return false;
        }
        return true;
    }

    private void sendDetail(CommandSender sender, String displayName, String periodLabel,
                            com.lawlessmc.playercouncil.managers.ActivityManager.PlayerActivityDetail d) {
        int minHours = plugin.getConfig().getInt("council.min-total-hours", 100);
        sender.sendMessage(mm.deserialize(
                "<gold>===== Activity: <white>" + displayName + "</white> (" + periodLabel + ") ====="));

        String rankStr = d.rank() > 0
                ? "<aqua>#" + d.rank() + "</aqua> of <aqua>" + d.eligibleCount() + "</aqua> eligible"
                : "<gray>unranked / not eligible</gray>";
        sender.sendMessage(mm.deserialize(
                "Score: <aqua>" + String.format("%.2f", d.totalScore()) + "</aqua>  |  Rank: " + rankStr));

        String hoursLine = "Total playtime: <aqua>" + d.totalPlayHours() + "h</aqua> (min "
                + minHours + "h " + (d.meetsMinHours() ? "<green>✓</green>" : "<red>✗</red>") + ")";
        String councilLine = d.councilMember() ? "<green>on council</green>" : "<gray>not on council</gray>";
        sender.sendMessage(mm.deserialize(hoursLine + "  |  " + councilLine));

        if (!d.enoughSnapshots()) {
            sender.sendMessage(mm.deserialize(
                    "<yellow>Not enough login/logout snapshots yet to measure a delta. Play and rejoin."));
            return;
        }

        sender.sendMessage(mm.deserialize("<gold>Stat breakdown:</gold>"));
        String topStat = null;
        double topContrib = -1;
        for (TrackedStats.StatBreakdown line : d.lines()) {
            String raw = TrackedStats.formatRaw(line.stat(), line.rawDelta());
            sender.sendMessage(mm.deserialize(
                    "  <yellow>" + line.stat() + "</yellow>  <white>" + raw + "</white>"
                            + "  <gray>→</gray> <aqua>" + String.format("%.2f", line.contribution())
                            + "</aqua> pts"));
            if (line.contribution() > topContrib) {
                topContrib = line.contribution();
                topStat = line.stat();
            }
        }
        if (topStat != null && topContrib > 0) {
            sender.sendMessage(mm.deserialize(
                    "<gray>Biggest contributor: <white>" + topStat + "</white>."));
        }
    }

    private record Period(long sinceMs, String label) {}

    private static Period parsePeriod(String arg) {
        if (arg != null && (arg.equalsIgnoreCase("week") || arg.equalsIgnoreCase("weekly"))) {
            return new Period(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7), "Last 7 days");
        }
        return new Period(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30), "Last 30 days");
    }

    private OfflinePlayer resolveOffline(String name) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(name)) return p;
        }
        @SuppressWarnings("deprecation")
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        return off;
    }
}
