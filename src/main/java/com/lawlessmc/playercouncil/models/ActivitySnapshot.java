package com.lawlessmc.playercouncil.models;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class ActivitySnapshot {

    private final UUID uuid;
    private final long timestamp;
    private final Map<String, Long> values;

    public ActivitySnapshot(UUID uuid, long timestamp, Map<String, Long> values) {
        this.uuid = uuid;
        this.timestamp = timestamp;
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public UUID getUuid() { return uuid; }
    public long getTimestamp() { return timestamp; }
    public Map<String, Long> getValues() { return values; }

    public long get(String stat) {
        return values.getOrDefault(stat, 0L);
    }

    public long getPlaytime() {
        return get("PLAY_ONE_MINUTE");
    }

    public ActivityDelta subtract(ActivitySnapshot earlier) {
        Map<String, Long> deltas = new LinkedHashMap<>();
        for (String key : values.keySet()) {
            long now = values.getOrDefault(key, 0L);
            long then = earlier.values.getOrDefault(key, 0L);
            deltas.put(key, Math.max(0, now - then));
        }
        for (String key : earlier.values.keySet()) {
            deltas.putIfAbsent(key, 0L);
        }
        return new ActivityDelta(deltas);
    }
}
