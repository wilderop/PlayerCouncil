package com.lawlessmc.playercouncil.models;

import java.util.*;

public class Proposal {

    public enum Type {
        BAN, PARDON, REBAN, REPARDON,
        GAMERULE, PLUGIN_ENABLE, PLUGIN_DISABLE,
        /** Advisory text for the server admin — no automatic action on pass. */
        SUGGESTION
    }

    private final int id;
    private final Type type;
    private final UUID proposer;
    private final String target; // player name, gamerule name, or plugin name
    private final String value;  // for gamerule: the new value; for ban types: required yes votes
    /** Optional free-text reason (ban proposals); stored for SmartBan / ban list. */
    private String reason;
    private final long createdAt;
    private final long expiresAt;
    private final Map<UUID, Boolean> votes; // true = yes, false = no
    private boolean cancelled;
    private boolean executed;
    /** Discord thread snowflake, if a bot thread was created for this proposal. */
    private String discordThreadId;

    public Proposal(int id, Type type, UUID proposer, String target, String value,
                    long createdAt, long expiresAt) {
        this.id = id;
        this.type = type;
        this.proposer = proposer;
        this.target = target;
        this.value = value;
        this.reason = null;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.votes = new LinkedHashMap<>();
        this.cancelled = false;
        this.executed = false;
        this.discordThreadId = null;
    }

    public int getId() { return id; }
    public Type getType() { return type; }
    public UUID getProposer() { return proposer; }
    public String getTarget() { return target; }
    public String getValue() { return value; }
    public String getReason() { return reason; }
    public long getCreatedAt() { return createdAt; }
    public long getExpiresAt() { return expiresAt; }
    public Map<UUID, Boolean> getVotes() { return Collections.unmodifiableMap(votes); }
    public boolean isCancelled() { return cancelled; }
    public boolean isExecuted() { return executed; }
    public String getDiscordThreadId() { return discordThreadId; }

    public void setReason(String reason) { this.reason = reason; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    public void setExecuted(boolean executed) { this.executed = executed; }
    public void setDiscordThreadId(String discordThreadId) { this.discordThreadId = discordThreadId; }

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
        String reasonSuffix = (reason != null && !reason.isBlank()) ? " (" + reason + ")" : "";
        return switch (type) {
            case BAN -> "Ban player " + target + reasonSuffix;
            case PARDON -> "Pardon player " + target + reasonSuffix;
            case REBAN -> "Re-ban player " + target + reasonSuffix;
            case REPARDON -> "Re-pardon player " + target + reasonSuffix;
            case GAMERULE -> "Set gamerule " + target + " to " + value;
            case PLUGIN_ENABLE -> "Enable plugin " + target;
            case PLUGIN_DISABLE -> "Disable plugin " + target;
            case SUGGESTION -> "Suggestion: " + target;
        };
    }
}
