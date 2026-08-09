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

import java.util.Map;
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
        recordIp(player);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            plugin.getCouncilManager().onPlayerJoin(player);
            plugin.getDisplayManager().onJoin(player);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        captureAndStore(player);
        plugin.getCouncilManager().onPlayerQuit(player);
        plugin.getDisplayManager().onQuit(player);
    }

    private void captureAndStore(Player player) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        long firstPlayed = player.getFirstPlayed();
        long now = System.currentTimeMillis();

        Map<String, Long> values = plugin.getTrackedStats().capture(player);
        long playtime = values.getOrDefault(Statistic.PLAY_ONE_MINUTE.name(), 0L);

        ActivitySnapshot snap = new ActivitySnapshot(uuid, now, values);
        plugin.getDatabaseManager().saveSnapshot(snap);
        plugin.getDatabaseManager().upsertPlayerMeta(uuid, name, firstPlayed, playtime);
    }

    private void recordIp(Player player) {
        try {
            if (player.getAddress() == null || player.getAddress().getAddress() == null) return;
            String ip = player.getAddress().getAddress().getHostAddress();
            plugin.getDatabaseManager().recordPlayerIp(player.getUniqueId(), ip);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to record IP for " + player.getName() + ": " + e.getMessage());
        }
    }
}
