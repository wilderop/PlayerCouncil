package com.lawlessmc.playercouncil.models;

import java.util.UUID;

public class ActivitySnapshot {

    private final UUID uuid;
    private final long timestamp;
    private final long playtime;
    private final long walk;
    private final long fly;
    private final long mobKills;

    public ActivitySnapshot(UUID uuid, long timestamp, long playtime, long walk, long fly, long mobKills) {
        this.uuid = uuid;
        this.timestamp = timestamp;
        this.playtime = playtime;
        this.walk = walk;
        this.fly = fly;
        this.mobKills = mobKills;
    }

    public UUID getUuid() { return uuid; }
    public long getTimestamp() { return timestamp; }
    public long getPlaytime() { return playtime; }
    public long getWalk() { return walk; }
    public long getFly() { return fly; }
    public long getMobKills() { return mobKills; }

    public ActivityDelta subtract(ActivitySnapshot earlier) {
        return new ActivityDelta(
                this.playtime - earlier.playtime,
                this.walk - earlier.walk,
                this.fly - earlier.fly,
                this.mobKills - earlier.mobKills
        );
    }
}
