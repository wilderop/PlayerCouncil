package com.lawlessmc.playercouncil.health;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes a small health.json the website ingest can ship to Montreal.
 */
public class HealthSnapshotter {

    private final PlayerCouncilPlugin plugin;
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final long startedAt = System.currentTimeMillis();
    private BukkitTask task;

    public HealthSnapshotter(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        int seconds = Math.max(10, plugin.getConfig().getInt("council-web.health-interval-seconds", 15));
        long ticks = seconds * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::captureAndWrite, ticks, ticks);
        Bukkit.getScheduler().runTask(plugin, this::captureAndWrite);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void captureAndWrite() {
        JsonObject o = new JsonObject();
        o.addProperty("updatedAt", System.currentTimeMillis() / 1000L);
        o.addProperty("uptimeSeconds", (System.currentTimeMillis() - startedAt) / 1000L);

        double[] tps = Bukkit.getTPS();
        o.addProperty("tps1", round(tps.length > 0 ? tps[0] : 0));
        o.addProperty("tps5", round(tps.length > 1 ? tps[1] : 0));
        o.addProperty("tps15", round(tps.length > 2 ? tps[2] : 0));
        o.addProperty("mspt", round(averageTickMs()));

        o.addProperty("players", Bukkit.getOnlinePlayers().size());
        o.addProperty("maxPlayers", Bukkit.getMaxPlayers());

        int view = 0;
        int sim = 0;
        World overworld = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (overworld != null) {
            view = overworld.getViewDistance();
            sim = overworld.getSimulationDistance();
        }
        o.addProperty("viewDistance", view);
        o.addProperty("simDistance", sim);

        Runtime rt = Runtime.getRuntime();
        o.addProperty("memUsedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        o.addProperty("memMaxMb", rt.maxMemory() / (1024 * 1024));

        String json = gson.toJson(o);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(json));
    }

    private void write(String json) {
        try {
            Path dest = plugin.getDataFolder().toPath().resolve("health.json");
            Path tmp = plugin.getDataFolder().toPath().resolve("health.json.tmp");
            Files.createDirectories(dest.getParent());
            Files.writeString(tmp, json);
            try {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("health snapshot failed: " + e.getMessage());
        }
    }

    private static double averageTickMs() {
        try {
            var method = Bukkit.getServer().getClass().getMethod("getAverageTickTime");
            Object v = method.invoke(Bukkit.getServer());
            if (v instanceof Number n) return n.doubleValue();
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
