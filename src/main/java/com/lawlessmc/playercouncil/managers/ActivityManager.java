package com.lawlessmc.playercouncil.managers;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import com.lawlessmc.playercouncil.models.ActivityDelta;
import com.lawlessmc.playercouncil.models.ActivitySnapshot;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ActivityManager {

    private final PlayerCouncilPlugin plugin;

    public ActivityManager(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<Double> getActivityScoreAsync(UUID uuid, long sinceMs) {
        return plugin.getDatabaseManager().getSnapshotsSinceAsync(uuid, sinceMs)
                .thenCompose(snaps -> {
                    if (snaps.size() >= 2) {
                        ActivitySnapshot first = snaps.get(0);
                        ActivitySnapshot last = snaps.get(snaps.size() - 1);
                        return CompletableFuture.completedFuture(score(last.subtract(first)));
                    }
                    return plugin.getDatabaseManager().getLatestSnapshotAsync(uuid)
                            .thenApply(latest -> {
                                if (latest == null) return 0.0;
                                return score(new ActivityDelta(
                                        latest.getPlaytime(), latest.getWalk(),
                                        latest.getFly(), latest.getMobKills()));
                            });
                });
    }

    private double score(ActivityDelta delta) {
        var cfg = plugin.getConfig().getConfigurationSection("activity.weights");
        double wPlay = cfg != null ? cfg.getDouble("playtime", 1.0) : 1.0;
        double wWalk = cfg != null ? cfg.getDouble("walk", 1.0) : 1.0;
        double wFly = cfg != null ? cfg.getDouble("fly", 1.0) : 1.0;
        double wMob = cfg != null ? cfg.getDouble("mob_kills", 1.0) : 1.0;
        return delta.score(wPlay, wWalk, wFly, wMob);
    }

    public CompletableFuture<List<Map.Entry<UUID, Double>>> getRankedPlayersAsync(long sinceMs, int minHours) {
        long minTicks = minHours * 20L * 3600L;

        return plugin.getDatabaseManager().getAllKnownPlayersAsync().thenCompose(players -> {
            List<CompletableFuture<AbstractMap.SimpleEntry<UUID, Double>>> futures = new ArrayList<>();

            for (UUID uuid : players.keySet()) {
                CompletableFuture<AbstractMap.SimpleEntry<UUID, Double>> f =
                        plugin.getDatabaseManager().getTotalPlaytimeAsync(uuid).thenCompose(totalPlay -> {
                            if (totalPlay < minTicks) {
                                return CompletableFuture.completedFuture(null);
                            }
                            return getActivityScoreAsync(uuid, sinceMs)
                                    .thenApply(score -> new AbstractMap.SimpleEntry<>(uuid, score));
                        });
                futures.add(f);
            }

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> {
                        List<Map.Entry<UUID, Double>> ranked = new ArrayList<>();
                        for (CompletableFuture<AbstractMap.SimpleEntry<UUID, Double>> f : futures) {
                            AbstractMap.SimpleEntry<UUID, Double> e = f.join();
                            if (e != null) ranked.add(e);
                        }
                        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
                        return ranked;
                    })
                    .thenCompose(ranked -> applyTiebreakersAsync(ranked));
        });
    }

    private CompletableFuture<List<Map.Entry<UUID, Double>>> applyTiebreakersAsync(
            List<Map.Entry<UUID, Double>> ranked) {
        List<CompletableFuture<long[]>> metaFutures = new ArrayList<>();
        for (Map.Entry<UUID, Double> e : ranked) {
            UUID uuid = e.getKey();
            metaFutures.add(
                    plugin.getDatabaseManager().getTotalPlaytimeAsync(uuid)
                            .thenCombine(plugin.getDatabaseManager().getFirstJoinAsync(uuid),
                                    (play, join) -> new long[]{play, join})
            );
        }
        return CompletableFuture.allOf(metaFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<long[]> meta = metaFutures.stream().map(CompletableFuture::join).toList();
                    List<Integer> indices = new ArrayList<>();
                    for (int i = 0; i < ranked.size(); i++) indices.add(i);
                    indices.sort((i, j) -> {
                        int cmp = Double.compare(ranked.get(j).getValue(), ranked.get(i).getValue());
                        if (cmp != 0) return cmp;
                        cmp = Long.compare(meta.get(j)[0], meta.get(i)[0]);
                        if (cmp != 0) return cmp;
                        return Long.compare(meta.get(i)[1], meta.get(j)[1]);
                    });
                    List<Map.Entry<UUID, Double>> sorted = new ArrayList<>();
                    for (int i : indices) sorted.add(ranked.get(i));
                    return sorted;
                });
    }
}
