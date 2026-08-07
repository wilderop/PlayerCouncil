package com.lawlessmc.playercouncil.managers;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class CouncilManager {

    private final PlayerCouncilPlugin plugin;
    private final Set<UUID> currentCouncil = Collections.synchronizedSet(new HashSet<>());
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();

    public CouncilManager(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    public void recalculateCouncil() {
        int size = plugin.getConfig().getInt("council.size", 12);
        int minHours = plugin.getConfig().getInt("council.min-total-hours", 100);
        long since = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000);

        plugin.getActivityManager().getRankedPlayersAsync(since, minHours)
                .thenCompose(this::filterSharedIpAlts)
                .thenAccept(ranked -> Bukkit.getScheduler().runTask(plugin, () -> applyRanking(ranked, size)))
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Council recalc failed: " + ex.getMessage());
                    return null;
                });
    }

    /**
     * Among accounts that have ever shared an IP, only the one with the most
     * total playtime remains eligible for council. Others are dropped.
     */
    private CompletableFuture<List<Map.Entry<UUID, Double>>> filterSharedIpAlts(
            List<Map.Entry<UUID, Double>> ranked) {
        if (ranked.isEmpty()) {
            return CompletableFuture.completedFuture(ranked);
        }
        List<UUID> uuids = ranked.stream().map(Map.Entry::getKey).toList();
        return plugin.getDatabaseManager().getIpRelatedGroupsAsync(uuids).thenCompose(related -> {
            List<CompletableFuture<AbstractMap.SimpleEntry<UUID, Long>>> hourFutures = new ArrayList<>();
            for (UUID u : uuids) {
                hourFutures.add(plugin.getDatabaseManager().getTotalPlaytimeAsync(u)
                        .thenApply(h -> new AbstractMap.SimpleEntry<>(u, h)));
            }
            return CompletableFuture.allOf(hourFutures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> {
                        Map<UUID, Long> hours = new HashMap<>();
                        for (var f : hourFutures) {
                            var e = f.join();
                            hours.put(e.getKey(), e.getValue());
                        }
                        Set<UUID> blocked = new HashSet<>();
                        for (UUID u : uuids) {
                            if (blocked.contains(u)) continue;
                            Set<UUID> group = related.getOrDefault(u, Set.of(u));
                            UUID best = u;
                            long bestHours = hours.getOrDefault(u, 0L);
                            for (UUID other : group) {
                                long h = hours.getOrDefault(other, 0L);
                                if (h > bestHours || (h == bestHours && other.toString().compareTo(best.toString()) < 0)) {
                                    best = other;
                                    bestHours = h;
                                }
                            }
                            for (UUID other : group) {
                                if (!other.equals(best)) blocked.add(other);
                            }
                        }
                        List<Map.Entry<UUID, Double>> filtered = new ArrayList<>();
                        for (Map.Entry<UUID, Double> e : ranked) {
                            if (!blocked.contains(e.getKey())) filtered.add(e);
                        }
                        int removed = ranked.size() - filtered.size();
                        if (removed > 0) {
                            plugin.getLogger().info("IP alt filter removed " + removed
                                    + " account(s) from council eligibility (kept highest playtime per shared IP).");
                        }
                        return filtered;
                    });
        });
    }

    private void applyRanking(List<Map.Entry<UUID, Double>> ranked, int size) {
        List<Map.Entry<UUID, String>> newCouncil = new ArrayList<>();
        Set<UUID> newSet = new HashSet<>();

        for (int i = 0; i < Math.min(size, ranked.size()); i++) {
            UUID uuid = ranked.get(i).getKey();
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            if (name == null) name = uuid.toString().substring(0, 8);
            newCouncil.add(Map.entry(uuid, name));
            newSet.add(uuid);
        }

        Set<UUID> removed = new HashSet<>(currentCouncil);
        removed.removeAll(newSet);
        Set<UUID> added = new HashSet<>(newSet);
        added.removeAll(currentCouncil);

        for (UUID uuid : removed) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                removeCouncilPermission(p);
                p.sendMessage(mm("<gray>[<gold>Council</gold>]</gray> <red>You are no longer a council member."));
            }
        }
        for (UUID uuid : added) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                grantCouncilPermission(p);
                p.sendMessage(mm("<gray>[<gold>Council</gold>]</gray> <green>You have been appointed to the Player Council!"));
            }
        }

        currentCouncil.clear();
        currentCouncil.addAll(newSet);
        plugin.getDatabaseManager().setCouncil(newCouncil);
        plugin.getDatabaseManager().log("Council recalculated. New size: " + newCouncil.size());
        plugin.getLogger().info("Council updated. Members: " + newCouncil.size());
    }

    public void grantCouncilPermission(Player player) {
        PermissionAttachment att = player.addAttachment(plugin);
        att.setPermission("playercouncil.council", true);
        attachments.put(player.getUniqueId(), att);
    }

    public void removeCouncilPermission(Player player) {
        PermissionAttachment att = attachments.remove(player.getUniqueId());
        if (att != null) {
            player.removeAttachment(att);
        }
    }

    public boolean isCouncilMember(UUID uuid) {
        return currentCouncil.contains(uuid);
    }

    public void loadFromDatabase() {
        plugin.getDatabaseManager().getCouncilUuidsAsync().thenAccept(list -> {
            currentCouncil.clear();
            currentCouncil.addAll(list);
        });
    }

    public List<UUID> getCouncilMembers() {
        return new ArrayList<>(currentCouncil);
    }

    public boolean isSystemActive() {
        int minRequired = plugin.getConfig().getInt("council.min-active-members", 3);
        return currentCouncil.size() >= minRequired;
    }

    public int getMinActiveMembers() {
        return plugin.getConfig().getInt("council.min-active-members", 3);
    }

    public void removeMember(UUID uuid) {
        currentCouncil.remove(uuid);
        plugin.getDatabaseManager().removeCouncilMember(uuid);
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            removeCouncilPermission(p);
        }
    }

    public void onPlayerJoin(Player player) {
        if (isCouncilMember(player.getUniqueId())) {
            grantCouncilPermission(player);
            player.sendMessage(mm("<gray>[<gold>Council</gold>]</gray> <green>You are currently a council member."));
            if (isSystemActive()) {
                player.sendMessage(mm("<gray>Type <yellow>/proposals</yellow> to see active proposals."));
            } else {
                player.sendMessage(mm("<gray>Council voting is not active yet (need " + getMinActiveMembers() + " members)."));
            }
        }
    }

    public void onPlayerQuit(Player player) {
        removeCouncilPermission(player);
    }

    private Component mm(String mini) {
        return MiniMessage.miniMessage().deserialize(mini);
    }
}
