package com.lawlessmc.playercouncil.models;

import java.util.*;

public class Proposal {

    public enum Type {
        BAN, PARDON, REBAN, REPARDON,
        GAMERULE, PLUGIN_ENABLE, PLUGIN_DISABLE
    }

    private final int id;
    private final Type type;
    private final UUID proposer;
    private final String target;
    private final String value;
    private final long createdAt;
    private final long expiresAt;
    private final Map<UUID, Boolean> votes;
    private boolean cancelled;
    private boolean executed;

    public Proposal(int id, Type type, UUID proposer, String target, String value,
                    long createdAt, long expiresAt) {
        this.id = id;
        this.type = type;
        this.proposer = proposer;
        this.target = target;
        this.value = value;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.votes = new LinkedHashMap<>();
        this.cancelled = false;
        this.executed = false;
    }

    public int getId() { return id; }
    public Type getType() { return type; }
    public UUID getProposer() { return proposer; }
    public String getTarget() { return target; }
    public String getValue() { return value; }
    public long getCreatedAt() { return createdAt; }
    public long getExpiresAt() { return expiresAt; }
    public Map<UUID, Boolean> getVotes() { return Collections.unmodifiableMap(votes); }
    public boolean isCancelled() { return cancelled; }
    public boolean isExecuted() { return executed; }

    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    public void setExecuted(boolean executed) { this.executed = executed; }

    public void addVote(UUID uuid, boolean yes) {
        votes.put(uuid, yes);
    }

    public int getYesCount() {
        return (int) votes.values().stream().filter(v -> v).count();
    }

    public int getNoCount() {
        return (int) votes.values().stream().filter(v -> !v).count();
    }

    public boolean hasVoted(UUID uuid) {
        return votes.containsKey(uuid);
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }

    public boolean isActive() {
        return !cancelled && !executed && !isExpired();
    }

    public String getDescription() {
        return switch (type) {
            case BAN -> "Ban player " + target;
            case PARDON -> "Pardon player " + target;
            case REBAN -> "Re-ban player " + target;
            case REPARDON -> "Re-pardon player " + target;
            case GAMERULE -> "Set gamerule " + target + " to " + value;
            case PLUGIN_ENABLE -> "Enable plugin " + target;
            case PLUGIN_DISABLE -> "Disable plugin " + target;
        };
    }
}
