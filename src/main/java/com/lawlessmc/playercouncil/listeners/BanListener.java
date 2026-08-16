package com.lawlessmc.playercouncil.listeners;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Locale;
import java.util.UUID;

/**
 * Records bans/unbans from kicks, vanilla ban list side-effects, and ban/pardon commands
 * so the council review system can see non-council bans (SmartBan / vanilla / staff).
 */
public class BanListener implements Listener {

    private final PlayerCouncilPlugin plugin;

    public BanListener(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        Player player = event.getPlayer();
        // After this tick, check whether they are name-banned
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (isNameBanned(player.getName())) {
                String reason = null;
                try {
                    var entry = Bukkit.getBanList(BanList.Type.NAME).getBanEntry(player.getName());
                    if (entry != null) reason = entry.getReason();
                } catch (Exception ignored) {}
                plugin.getBanReviewManager().recordBan(
                        player.getUniqueId(),
                        player.getName(),
                        reason != null ? reason : "Kicked/banned",
                        "kick",
                        null,
                        "unknown");
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage();
        if (msg == null || msg.length() < 4) return;
        handleBanCommand(event.getPlayer().getUniqueId(), event.getPlayer().getName(), msg.substring(1));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        String cmd = event.getCommand();
        if (cmd == null || cmd.isBlank()) return;
        handleBanCommand(null, "console", cmd);
    }

    private void handleBanCommand(UUID actorUuid, String actorName, String raw) {
        String[] parts = raw.trim().split("\\s+");
        if (parts.length < 2) return;
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        // strip leading plugin: namespace if present
        int colon = cmd.indexOf(':');
        if (colon >= 0) cmd = cmd.substring(colon + 1);

        boolean isBan = cmd.equals("ban") || cmd.equals("ban-ip") || cmd.equals("tempban")
                || cmd.equals("banip") || cmd.equals("smartban");
        boolean isUnban = cmd.equals("unban") || cmd.equals("pardon") || cmd.equals("unban-ip")
                || cmd.equals("pardon-ip");

        if (!isBan && !isUnban) return;

        String targetName = parts[1];
        OfflinePlayer off = Bukkit.getOfflinePlayer(targetName);
        UUID targetUuid = off.getUniqueId();

        if (isUnban) {
            // Defer slightly so the ban plugin can process
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    plugin.getBanReviewManager().recordUnban(targetUuid, targetName), 5L);
            return;
        }

        String reason = parts.length > 2
                ? String.join(" ", java.util.Arrays.copyOfRange(parts, 2, parts.length))
                : null;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Confirm they ended up banned (SmartBan / vanilla)
            if (isNameBanned(targetName) || isNameBanned(off.getName())) {
                plugin.getBanReviewManager().recordBan(
                        targetUuid,
                        off.getName() != null ? off.getName() : targetName,
                        reason,
                        "command",
                        actorUuid,
                        actorName);
            } else {
                // Still record — SmartBan may delay writing to Bukkit BanList
                plugin.getBanReviewManager().recordBan(
                        targetUuid,
                        off.getName() != null ? off.getName() : targetName,
                        reason,
                        "command",
                        actorUuid,
                        actorName);
            }
        }, 5L);
    }

    private static boolean isNameBanned(String name) {
        if (name == null || name.isBlank()) return false;
        try {
            return Bukkit.getBanList(BanList.Type.NAME).isBanned(name);
        } catch (Exception e) {
            return false;
        }
    }
}
