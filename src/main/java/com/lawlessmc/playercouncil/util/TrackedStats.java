package com.lawlessmc.playercouncil.util;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.util.*;

public class TrackedStats {

    public record Definition(String name, Statistic statistic, double weight, double scale) {
        public double contribution(long delta) {
            if (scale <= 0) return 0;
            return weight * (Math.max(0, delta) / scale);
        }
    }

    private final PlayerCouncilPlugin plugin;
    private List<Definition> definitions = List.of();

    public TrackedStats(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        List<Definition> loaded = new ArrayList<>();
        List<?> list = plugin.getConfig().getList("activity.tracked-stats");
        if (list != null && !list.isEmpty()) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> map)) continue;
                Object nameObj = map.get("stat");
                if (nameObj == null) continue;
                String name = nameObj.toString().toUpperCase(Locale.ROOT);
                Statistic stat = resolve(name);
                if (stat == null) {
                    plugin.getLogger().warning("Unknown or unsupported statistic in config: " + name);
                    continue;
                }
                double weight = toDouble(map.get("weight"), 1.0);
                double scale = toDouble(map.get("scale"), defaultScale(stat));
                loaded.add(new Definition(stat.name(), stat, weight, scale));
            }
        }
        if (loaded.isEmpty()) {
            loaded.add(def(Statistic.PLAY_ONE_MINUTE, 1.0, 72_000.0));
            loaded.add(def(Statistic.WALK_ONE_CM, 1.0, 100_000.0));
            loaded.add(def(Statistic.AVIATE_ONE_CM, 1.0, 100_000.0));
            loaded.add(def(Statistic.MOB_KILLS, 1.0, 1.0));
        }
        this.definitions = List.copyOf(loaded);
    }

    private static Definition def(Statistic s, double w, double scale) {
        return new Definition(s.name(), s, w, scale);
    }

    private static double toDouble(Object o, double fallback) {
        if (o instanceof Number n) return n.doubleValue();
        if (o != null) {
            try { return Double.parseDouble(o.toString()); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    public static double defaultScale(Statistic stat) {
        String n = stat.name();
        if (n.equals("PLAY_ONE_MINUTE")) return 72_000.0;
        if (n.endsWith("_ONE_CM")) return 100_000.0;
        return 1.0;
    }

    public static Statistic resolve(String name) {
        try {
            Statistic s = Statistic.valueOf(name.toUpperCase(Locale.ROOT));
            if (s.getType() != Statistic.Type.UNTYPED) return null;
            return s;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public List<Definition> getDefinitions() {
        return definitions;
    }

    public Map<String, Long> capture(Player player) {
        Map<String, Long> values = new LinkedHashMap<>();
        for (Definition d : definitions) {
            try {
                values.put(d.name(), (long) player.getStatistic(d.statistic()));
            } catch (Exception e) {
                values.put(d.name(), 0L);
            }
        }
        if (!values.containsKey(Statistic.PLAY_ONE_MINUTE.name())) {
            try {
                values.put(Statistic.PLAY_ONE_MINUTE.name(),
                        (long) player.getStatistic(Statistic.PLAY_ONE_MINUTE));
            } catch (Exception ignored) {}
        }
        return values;
    }

    public double score(Map<String, Long> deltas) {
        double total = 0;
        for (Definition d : definitions) {
            long delta = deltas.getOrDefault(d.name(), 0L);
            total += d.contribution(delta);
        }
        return total;
    }

    public void saveToConfig() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Definition d : definitions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stat", d.name());
            row.put("weight", d.weight());
            row.put("scale", d.scale());
            list.add(row);
        }
        plugin.getConfig().set("activity.tracked-stats", list);
        plugin.getConfig().set("activity.weights", null);
        plugin.saveConfig();
        reload();
    }

    public boolean add(String name, double weight, double scale) {
        Statistic s = resolve(name);
        if (s == null) return false;
        List<Definition> next = new ArrayList<>();
        for (Definition d : definitions) {
            if (!d.name().equals(s.name())) next.add(d);
        }
        next.add(new Definition(s.name(), s, weight, scale > 0 ? scale : defaultScale(s)));
        definitions = List.copyOf(next);
        saveToConfig();
        return true;
    }

    public boolean remove(String name) {
        Statistic s = resolve(name);
        String key = s != null ? s.name() : name.toUpperCase(Locale.ROOT);
        List<Definition> next = new ArrayList<>();
        boolean removed = false;
        for (Definition d : definitions) {
            if (d.name().equals(key)) { removed = true; continue; }
            next.add(d);
        }
        if (!removed) return false;
        definitions = List.copyOf(next);
        saveToConfig();
        return true;
    }

    public boolean setWeight(String name, double weight) {
        return update(name, weight, null);
    }

    public boolean setScale(String name, double scale) {
        return update(name, null, scale);
    }

    private boolean update(String name, Double weight, Double scale) {
        Statistic s = resolve(name);
        String key = s != null ? s.name() : name.toUpperCase(Locale.ROOT);
        List<Definition> next = new ArrayList<>();
        boolean found = false;
        for (Definition d : definitions) {
            if (d.name().equals(key)) {
                found = true;
                next.add(new Definition(
                        d.name(), d.statistic(),
                        weight != null ? weight : d.weight(),
                        scale != null && scale > 0 ? scale : d.scale()
                ));
            } else {
                next.add(d);
            }
        }
        if (!found) return false;
        definitions = List.copyOf(next);
        saveToConfig();
        return true;
    }
}
