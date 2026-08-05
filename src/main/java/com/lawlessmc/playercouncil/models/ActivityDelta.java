package com.lawlessmc.playercouncil.models;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class ActivityDelta {

    private final Map<String, Long> deltas;

    public ActivityDelta(Map<String, Long> deltas) {
        Map<String, Long> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : deltas.entrySet()) {
            copy.put(e.getKey(), Math.max(0, e.getValue()));
        }
        this.deltas = Collections.unmodifiableMap(copy);
    }

    public Map<String, Long> getDeltas() {
        return deltas;
    }

    public long get(String stat) {
        return deltas.getOrDefault(stat, 0L);
    }
}
