package com.lawlessmc.playercouncil.listeners;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import com.lawlessmc.playercouncil.models.ActivitySnapshot;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public class PlayerListener implements Listener {

    private final PlayerCouncilPlugin plugin;

    public PlayerListener(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        captureAndStore(player);
        plugin.getServer().getScheduler().runTask(plugin, () ->
                plugin.getCouncilManager().onPlayerJoin(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        captureAndStore(player);
        plugin.getCouncilManager().onPlayerQuit(player);
    }

    private void captureAndStore(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        long firstPlayed = player.getFirstPlayed();
        long playtime = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
        long walk = player.getStatistic(Statistic.WALK_ONE_CM);
        long fly = player.getStatistic(Statistic.AVIATE_ONE_CM);
        long mobKills = player.getStatistic(Statistic.MOB_KILLS);
        long now = System.currentTimeMillis();

        ActivitySnapshot snap = new ActivitySnapshot(uuid, now, playtime, walk, fly, mobKills);
        plugin.getDatabaseManager().saveSnapshot(snap);
        plugin.getDatabaseManager().upsertPlayerMeta(uuid, name, firstPlayed, playtime);
    }
}
