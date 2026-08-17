package com.lawlessmc.playercouncil.managers;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.Locale;

public class DisplayManager {

    private final PlayerCouncilPlugin plugin;
    private final Set<UUID> optedIn = ConcurrentHashMap.newKeySet();
    private volatile List<Map.Entry<UUID, Double>> cachedRanked = List.of();
    private int taskId = -1;

    public DisplayManager(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        if (!isFeatureEnabled()) {
            plugin.getLogger().info("Activity scoreboard display is disabled (display.enabled=false).");
            return;
        }
        plugin.getDatabaseManager().loadScoreboardOptInsAsync().thenAccept(set -> {
            optedIn.clear();
            optedIn.addAll(set);
        });
        int seconds = Math.max(30, plugin.getConfig().getInt("display.refresh-seconds", 300));
        long ticks = seconds * 20L;
        refreshCacheAndBoards();
        taskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refreshCacheAndBoards,
                ticks, ticks).getTaskId();
        plugin.getLogger().info("Activity scoreboard display enabled (refresh every " + seconds + "s).");
    }

    public void stop() {
        if (taskId >= 0) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        // onDisable runs on the main thread; never schedule tasks while the plugin is disabled
        for (Player p : Bukkit.getOnlinePlayers()) {
            clearBoard(p);
        }
    }

    public boolean isFeatureEnabled() {
        return plugin.getConfig().getBoolean("display.enabled", false);
    }

    public boolean isOptedIn(UUID uuid) {
        return optedIn.contains(uuid);
    }

    public Optional<Boolean> toggle(Player player) {
        if (!isFeatureEnabled()) {
            return Optional.empty();
        }
        UUID uuid = player.getUniqueId();
        boolean nowOn;
        if (optedIn.contains(uuid)) {
            optedIn.remove(uuid);
            nowOn = false;
            plugin.getDatabaseManager().setScoreboardOptIn(uuid, false);
            clearBoard(player);
        } else {
            optedIn.add(uuid);
            nowOn = true;
            plugin.getDatabaseManager().setScoreboardOptIn(uuid, true);
            applyBoard(player);
        }
        return Optional.of(nowOn);
    }

    public void onJoin(Player player) {
        if (!isFeatureEnabled()) return;
        plugin.getDatabaseManager().isScoreboardOptInAsync(player.getUniqueId()).thenAccept(on -> {
            if (on) {
                optedIn.add(player.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () -> applyBoard(player));
            }
        });
    }

    public void onQuit(Player player) {
        clearBoard(player);
    }

    private void refreshCacheAndBoards() {
        if (!isFeatureEnabled()) return;
        long since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30);
        int minHours = plugin.getConfig().getInt("council.min-total-hours", 100);
        plugin.getActivityManager().getRankedPlayersAsync(since, minHours)
                .thenAccept(ranked -> {
                    cachedRanked = List.copyOf(ranked);
                    Bukkit.getScheduler().runTask(plugin, this::pushToOptedInPlayers);
                });
    }

    private void pushToOptedInPlayers() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (optedIn.contains(p.getUniqueId())) {
                applyBoard(p);
            }
        }
    }

    private void applyBoard(Player player) {
        if (!isFeatureEnabled() || !optedIn.contains(player.getUniqueId())) {
            clearBoard(player);
            return;
        }
        int topN = Math.max(1, Math.min(15, plugin.getConfig().getInt("display.top-n", 10)));
        String title = plugin.getConfig().getString("display.title", "Council Race");

        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = board.registerNewObjective("pc_activity", Criteria.DUMMY,
                Component.text(title, NamedTextColor.GOLD, TextDecoration.BOLD));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        List<Map.Entry<UUID, Double>> ranked = cachedRanked;
        int lines = Math.min(topN, ranked.size());
        for (int i = 0; i < lines; i++) {
            UUID uuid = ranked.get(i).getKey();
            double score = ranked.get(i).getValue();
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            if (name == null) name = uuid.toString().substring(0, 8);
            if (name.length() > 12) name = name.substring(0, 12);
            boolean council = plugin.getCouncilManager().isCouncilMember(uuid);
            String entry = (i + 1) + ". " + name + (council ? " ★" : "");
            entry = padUnique(entry, i);
            obj.getScore(entry).setScore((int) Math.round(score * 100));
        }

        if (lines == 0) {
            obj.getScore("No data yet").setScore(0);
        }

        int selfRank = -1;
        double selfScore = 0;
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).getKey().equals(player.getUniqueId())) {
                selfRank = i + 1;
                selfScore = ranked.get(i).getValue();
                break;
            }
        }
        if (selfRank > 0) {
            String you = "You: #" + selfRank + " (" + String.format(Locale.US, "%.1f", selfScore) + ")";
            obj.getScore(you).setScore(-1);
        }

        player.setScoreboard(board);
    }

    private static String padUnique(String entry, int index) {
        if (index == 0) return entry;
        return entry + "§" + Integer.toHexString(index % 16);
    }

    private void clearBoard(Player player) {
        try {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        } catch (Exception ignored) {
        }
    }
}
