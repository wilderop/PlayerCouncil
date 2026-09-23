package com.lawlessmc.playercouncil.models;

import java.util.UUID;

public final class BugReport {

    public static final String OPEN = "open";
    public static final String NEEDS_INFO = "needs_info";
    public static final String IN_PROGRESS = "in_progress";
    public static final String FIXED = "fixed";
    public static final String CLOSED = "closed";

    public final int id;
    public final UUID reporterUuid;
    public final String reporterName;
    public final String title;
    public final String status;
    public final long createdAt;
    public final long updatedAt;
    public final UUID closedByUuid;
    public final String closedByName;
    public final String closeNote;
    public final int reportCount;
    public final String location;
    public final String question;

    public BugReport(int id, UUID reporterUuid, String reporterName, String title, String status,
                     long createdAt, long updatedAt, UUID closedByUuid, String closedByName,
                     String closeNote, int reportCount, String location, String question) {
        this.id = id;
        this.reporterUuid = reporterUuid;
        this.reporterName = reporterName;
        this.title = title;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.closedByUuid = closedByUuid;
        this.closedByName = closedByName;
        this.closeNote = closeNote;
        this.reportCount = reportCount;
        this.location = location;
        this.question = question;
    }

    public boolean isActive() {
        return OPEN.equals(status) || NEEDS_INFO.equals(status) || IN_PROGRESS.equals(status);
    }

    public String statusLabel() {
        return switch (status) {
            case NEEDS_INFO -> "NEEDS INFO";
            case IN_PROGRESS -> "IN PROGRESS";
            case FIXED -> "FIXED";
            case CLOSED -> "CLOSED";
            default -> "OPEN";
        };
    }

    public static final class Event {
        public final long at;
        public final String kind;
        public final String actorName;
        public final String text;

        public Event(long at, String kind, String actorName, String text) {
            this.at = at;
            this.kind = kind;
            this.actorName = actorName;
            this.text = text;
        }
    }
}
