package com.lawlessmc.playercouncil.models;

public class ActivityDelta {

    private final long playtime;
    private final long walk;
    private final long fly;
    private final long mobKills;

    public ActivityDelta(long playtime, long walk, long fly, long mobKills) {
        this.playtime = Math.max(0, playtime);
        this.walk = Math.max(0, walk);
        this.fly = Math.max(0, fly);
        this.mobKills = Math.max(0, mobKills);
    }

    public long getPlaytime() { return playtime; }
    public long getWalk() { return walk; }
    public long getFly() { return fly; }
    public long getMobKills() { return mobKills; }

    public double score(double wPlay, double wWalk, double wFly, double wMob) {
        double playHours = playtime / 20.0 / 3600.0;
        double walkKm = walk / 100000.0;
        double flyKm = fly / 100000.0;
        return (playHours * wPlay) + (walkKm * wWalk) + (flyKm * wFly) + (mobKills * wMob);
    }
}
