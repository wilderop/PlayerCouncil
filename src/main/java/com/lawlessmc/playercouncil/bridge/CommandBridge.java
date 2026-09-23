package com.lawlessmc.playercouncil.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.util.Pool;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.JedisSentinelPool;
import org.bukkit.scheduler.BukkitRunnable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class CommandBridge {
    public static final String CMD = "pc:cmd";
    public static final String REPLY = "pc:reply";
    private static final Gson GSON = new Gson();
    private static final Set<String> COMMANDS = Set.of(
            "activity", "council", "proposals", "propose", "proposal",
            "councilvote", "cvote", "pcvote", "cancelproposal",
            "councilweb", "webcode", "councilcode", "counciladmin",
            "councilreview", "councilboard", "pcboard", "activityboard",
            "cape", "spawncape", "topkiller",
            "top", "topgui", "topplayers", "stats", "tpstats",
            "bug", "bugs", "bugnotify"
    );

    private static final String SENTINEL_MASTER = "azpbmd";
    private static final Set<String> SENTINELS = Set.of(
            "127.0.0.1:26379", "127.0.0.1:26379", "127.0.0.1:26379");

    private final PlayerCouncilPlugin plugin;
    private Pool<Jedis> pool;
    private Thread sub;
    private volatile JedisPubSub subListener;

    public CommandBridge(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Path passFile = Path.of("redis.pass");
        String password = "";
        try {
            if (Files.isRegularFile(passFile)) {
                password = Files.readString(passFile).trim();
            }
        } catch (Exception ignored) {
        }
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(4);
        try {
            Set<String> sentinels = new LinkedHashSet<>(SENTINELS);
            try {
                pool = password.isBlank()
                        ? new JedisSentinelPool(SENTINEL_MASTER, sentinels, cfg, 2000)
                        : new JedisSentinelPool(SENTINEL_MASTER, sentinels, cfg, 2000, password);
                try (Jedis j = pool.getResource()) {
                    j.ping();
                }
                plugin.getLogger().info("Council command bridge Redis via Sentinel master=" + SENTINEL_MASTER);
            } catch (Exception sentinelErr) {
                plugin.getLogger().warning("Council Sentinel failed (" + sentinelErr.getMessage()
                        + "), falling back to 127.0.0.1");
                if (pool != null) {
                    try { pool.close(); } catch (Exception ignored) {}
                }
                pool = password.isBlank()
                        ? new JedisPool(cfg, "127.0.0.1", 6379, 2000)
                        : new JedisPool(cfg, "127.0.0.1", 6379, 2000, password);
                try (Jedis j = pool.getResource()) {
                    j.ping();
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Council command bridge Redis failed: " + e.getMessage());
            if (pool != null) pool.close();
            pool = null;
            return;
        }
        sub = new Thread(this::listen, "pc-cmd-sub");
        sub.setDaemon(true);
        sub.start();
        plugin.getLogger().info("Council command bridge listening (fabric /activity etc).");
    }

    public void stop() {
        try {
            if (subListener != null) subListener.unsubscribe();
        } catch (Exception ignored) {
        }
        if (sub != null) sub.interrupt();
        if (pool != null) {
            try {
                pool.close();
            } catch (Exception ignored) {
            }
        }
        pool = null;
    }

    private void listen() {
        while (pool != null && !Thread.currentThread().isInterrupted()) {
            try (Jedis j = pool.getResource()) {
                subListener = new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        Bukkit.getScheduler().runTask(plugin, () -> handle(message));
                    }
                };
                j.subscribe(subListener, CMD);
            } catch (Exception e) {
                if (pool == null || Thread.currentThread().isInterrupted()) return;
                plugin.getLogger().log(Level.WARNING, "pc:cmd subscribe ended: " + e.getMessage());
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void handle(String raw) {
        JsonObject o;
        try {
            o = GSON.fromJson(raw, JsonObject.class);
        } catch (Exception e) {
            return;
        }
        if (o == null || !o.has("id") || !o.has("uuid") || !o.has("cmd")) return;
        String id = o.get("id").getAsString();
        UUID uuid;
        try {
            uuid = UUID.fromString(o.get("uuid").getAsString());
        } catch (Exception e) {
            return;
        }
        String name = o.has("name") ? o.get("name").getAsString() : uuid.toString();
        String cmdName = o.get("cmd").getAsString().toLowerCase(Locale.ROOT);
        if (cmdName.startsWith("/")) cmdName = cmdName.substring(1);
        if (!COMMANDS.contains(cmdName)) {
            reply(id, uuid, List.of("<red>Unknown command."));
            return;
        }

        String[] args = new String[0];
        if (o.has("args") && o.get("args").isJsonArray()) {
            JsonArray arr = o.getAsJsonArray("args");
            List<String> list = new ArrayList<>();
            arr.forEach(el -> list.add(el.getAsString()));
            args = list.toArray(new String[0]);
        }

        if (cmdName.equals("councilboard") || cmdName.equals("pcboard") || cmdName.equals("activityboard")) {
            reply(id, uuid, List.of("<gray>Activity scoreboard only works on survival."));
            return;
        }
        if (cmdName.equals("top") || cmdName.equals("topgui") || cmdName.equals("topplayers")
                || cmdName.equals("stats") || cmdName.equals("tpstats")) {
            reply(id, uuid, TopPlayersDump.lines(args));
            return;
        }

        OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
        boolean op = off.isOp();
        boolean council = plugin.getCouncilManager().isCouncilMember(uuid);
        if (cmdName.equals("counciladmin") && !op) {
            reply(id, uuid, List.of("<red>Unknown command. Type \"/help\" for help."));
            return;
        }
        if ((cmdName.equals("propose") || cmdName.equals("proposal")
                || cmdName.equals("councilvote") || cmdName.equals("cvote") || cmdName.equals("pcvote")
                || cmdName.equals("cancelproposal") || cmdName.equals("councilreview"))
                && !council && !op) {
            reply(id, uuid, List.of("<red>Only council members can do that."));
            return;
        }

        BridgedSender sender = new BridgedSender(plugin, uuid, name, op, council);
        boolean allowEmpty = cmdName.equals("bugnotify");
        if (cmdName.equals("bugnotify")) {
            com.lawlessmc.playercouncil.commands.BugCommand.sendPendingTo(plugin, sender);
        } else {
            String canon = canonical(cmdName);
            PluginCommand cmd = plugin.getCommand(canon);
            if (cmd == null) {
                cmd = Bukkit.getPluginCommand(canon);
            }
            if (cmd == null || cmd.getExecutor() == null) {
                reply(id, uuid, List.of("<red>That command is not loaded on survival."));
                return;
            }
            CommandExecutor exec = cmd.getExecutor();
            exec.onCommand(sender, cmd, cmdName, args);
        }
        new BukkitRunnable() {
            int waited;
            int stable;
            int lastSize = -1;

            @Override
            public void run() {
                waited += 5;
                List<String> lines = sender.lines();
                if (lines.size() == lastSize) {
                    stable += 5;
                } else {
                    lastSize = lines.size();
                    stable = 0;
                }
                boolean ready = !lines.isEmpty() && stable >= 20;
                boolean timeout = waited >= 80;
                if (!ready && !timeout) return;
                cancel();
                if (lines.isEmpty()) {
                    if (allowEmpty) {
                        reply(id, uuid, List.of());
                    } else {
                        reply(id, uuid, List.of("<gray>No response from survival. Try again."));
                    }
                } else {
                    reply(id, uuid, lines);
                }
            }
        }.runTaskTimer(plugin, 5L, 5L);
    }

    private static String canonical(String label) {
        return switch (label) {
            case "proposal" -> "propose";
            case "cvote", "pcvote" -> "councilvote";
            case "webcode", "councilcode" -> "councilweb";
            case "pcboard", "activityboard" -> "councilboard";
            case "spawncape" -> "cape";
            case "bugs" -> "bug";
            default -> label;
        };
    }

    private void reply(String id, UUID uuid, List<String> lines) {
        if (pool == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("uuid", uuid.toString());
        JsonArray arr = new JsonArray();
        for (String line : lines) arr.add(line);
        o.add("lines", arr);
        String json = GSON.toJson(o);
        try (Jedis j = pool.getResource()) {
            j.publish(REPLY, json);
            j.setex("pc:reply:" + id, 30, json);
        } catch (Exception e) {
            plugin.getLogger().warning("pc:reply: " + e.getMessage());
        }
    }
}
