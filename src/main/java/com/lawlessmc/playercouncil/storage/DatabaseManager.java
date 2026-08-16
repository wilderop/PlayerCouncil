package com.lawlessmc.playercouncil.storage;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import com.lawlessmc.playercouncil.models.ActivitySnapshot;
import com.lawlessmc.playercouncil.models.Proposal;
import org.bukkit.Bukkit;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * SQLite persistence for activity snapshots, council seats, proposals, votes,
 * and tracked bans for the automated review system.
 */
public class DatabaseManager {

    private final PlayerCouncilPlugin plugin;
    private Connection connection;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PlayerCouncil-DB");
        t.setDaemon(true);
        return t;
    });

    public DatabaseManager(PlayerCouncilPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) dataFolder.mkdirs();
            File dbFile = new File(dataFolder, "playercouncil.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("""
                    CREATE TABLE IF NOT EXISTS players (
                        uuid TEXT PRIMARY KEY,
                        name TEXT,
                        first_played BIGINT,
                        playtime BIGINT DEFAULT 0,
                        last_seen BIGINT
                    )
                    """);
                st.execute("""
                    CREATE TABLE IF NOT EXISTS activity_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT NOT NULL,
                        captured_at BIGINT NOT NULL,
                        stats TEXT NOT NULL
                    )
                    """);
                st.execute("CREATE INDEX IF NOT EXISTS idx_snapshots_uuid_time ON activity_snapshots(uuid, captured_at)");
                st.execute("""
                    CREATE TABLE IF NOT EXISTS council_seats (
                        uuid TEXT PRIMARY KEY,
                        name TEXT,
                        rank INTEGER,
                        seated_at BIGINT
                    )
                    """);
                st.execute("""
                    CREATE TABLE IF NOT EXISTS proposals (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        type TEXT NOT NULL,
                        proposer TEXT NOT NULL,
                        target TEXT,
                        value TEXT,
                        reason TEXT,
                        created_at BIGINT NOT NULL,
                        expires_at BIGINT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'ACTIVE',
                        discord_thread_id TEXT
                    )
                    """);
                st.execute("""
                    CREATE TABLE IF NOT EXISTS votes (
                        proposal_id INTEGER NOT NULL,
                        voter TEXT NOT NULL,
                        yes INTEGER NOT NULL,
                        voted_at BIGINT NOT NULL,
                        PRIMARY KEY (proposal_id, voter),
                        FOREIGN KEY (proposal_id) REFERENCES proposals(id)
                    )
                    """);
                st.execute("""
                    CREATE TABLE IF NOT EXISTS logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        message TEXT NOT NULL,
                        created_at BIGINT NOT NULL
                    )
                    """);
                st.execute("""
                    CREATE TABLE IF NOT EXISTS player_ips (
                        uuid TEXT NOT NULL,
                        ip TEXT NOT NULL,
                        first_seen BIGINT,
                        last_seen BIGINT,
                        PRIMARY KEY (uuid, ip)
                    )
                    """);
                st.execute("""
                    CREATE TABLE IF NOT EXISTS ban_ladder (
                        uuid TEXT PRIMARY KEY,
                        name TEXT,
                        stage INTEGER DEFAULT 0,
                        updated_at BIGINT
                    )
                    """);
                st.execute("""
                    CREATE TABLE IF NOT EXISTS ban_propose_cooldown (
                        uuid TEXT PRIMARY KEY,
                        until_ms BIGINT NOT NULL
                    )
                    """);
                st.execute("""
                    CREATE TABLE IF NOT EXISTS pending_plugin_actions (
                        plugin_name TEXT PRIMARY KEY,
                        enable INTEGER NOT NULL
                    )
                    """);
                st.execute("""
                    CREATE TABLE IF NOT EXISTS tracked_bans (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        target_uuid TEXT NOT NULL,
                        target_name TEXT NOT NULL,
                        reason TEXT,
                        source TEXT,
                        banned_by_uuid TEXT,
                        banned_by_name TEXT,
                        banned_at BIGINT NOT NULL,
                        first_prompted_at BIGINT,
                        active INTEGER NOT NULL DEFAULT 1
                    )
                    """);
                st.execute("CREATE INDEX IF NOT EXISTS idx_tracked_bans_active ON tracked_bans(active, banned_at)");
                st.execute("""
                    CREATE TABLE IF NOT EXISTS ban_review_responses (
                        ban_id INTEGER NOT NULL,
                        council_uuid TEXT NOT NULL,
                        response TEXT NOT NULL,
                        responded_at BIGINT NOT NULL,
                        PRIMARY KEY (ban_id, council_uuid),
                        FOREIGN KEY (ban_id) REFERENCES tracked_bans(id)
                    )
                    """);
            }
            plugin.getLogger().info("Database initialized.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to init database", e);
        }
    }

    public void close() {
        dbExecutor.shutdown();
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("Error closing DB: " + e.getMessage());
        }
    }

    private <T> CompletableFuture<T> supplyAsync(java.util.function.Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, dbExecutor);
    }

    private CompletableFuture<Void> runAsync(Runnable runnable) {
        return CompletableFuture.runAsync(runnable, dbExecutor);
    }

    public void log(String message) {
        long now = System.currentTimeMillis();
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO logs (message, created_at) VALUES (?, ?)")) {
                ps.setString(1, message);
                ps.setLong(2, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Log write failed: " + e.getMessage());
            }
        });
    }

    // ---------- Players / activity ----------

    public void upsertPlayerMeta(UUID uuid, String name, long firstPlayed, long playtime) {
        long now = System.currentTimeMillis();
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO players (uuid, name, first_played, playtime, last_seen) VALUES (?,?,?,?,?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET name=excluded.name, playtime=excluded.playtime, last_seen=excluded.last_seen")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setLong(3, firstPlayed);
                ps.setLong(4, playtime);
                ps.setLong(5, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("upsertPlayerMeta failed: " + e.getMessage());
            }
        });
    }

    public void saveSnapshot(ActivitySnapshot snap) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO activity_snapshots (uuid, captured_at, stats) VALUES (?,?,?)")) {
                ps.setString(1, snap.uuid().toString());
                ps.setLong(2, snap.capturedAt());
                ps.setString(3, encodeStats(snap.values()));
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("saveSnapshot failed: " + e.getMessage());
            }
        });
    }

    public void pruneOldSnapshots(int keepDays) {
        long cutoff = System.currentTimeMillis() - keepDays * 24L * 60 * 60 * 1000;
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM activity_snapshots WHERE captured_at < ?")) {
                ps.setLong(1, cutoff);
                int n = ps.executeUpdate();
                if (n > 0) plugin.getLogger().info("Pruned " + n + " old activity snapshots.");
            } catch (SQLException e) {
                plugin.getLogger().warning("pruneOldSnapshots failed: " + e.getMessage());
            }
        });
    }

    public void recordPlayerIp(UUID uuid, String ip) {
        long now = System.currentTimeMillis();
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO player_ips (uuid, ip, first_seen, last_seen) VALUES (?,?,?,?) "
                            + "ON CONFLICT(uuid, ip) DO UPDATE SET last_seen=excluded.last_seen")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, ip);
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("recordPlayerIp failed: " + e.getMessage());
            }
        });
    }

    // ---------- Council seats ----------

    public CompletableFuture<List<UUID>> getCouncilUuidsAsync() {
        return supplyAsync(() -> {
            List<UUID> list = new ArrayList<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid FROM council_seats ORDER BY rank")) {
                while (rs.next()) {
                    try { list.add(UUID.fromString(rs.getString("uuid"))); } catch (Exception ignored) {}
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("getCouncilUuids failed: " + e.getMessage());
            }
            return list;
        });
    }

    public void saveCouncilSeats(List<UUID> uuids, Map<UUID, String> names) {
        runAsync(() -> {
            try {
                connection.setAutoCommit(false);
                try (Statement st = connection.createStatement()) {
                    st.execute("DELETE FROM council_seats");
                }
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO council_seats (uuid, name, rank, seated_at) VALUES (?,?,?,?)")) {
                    long now = System.currentTimeMillis();
                    int rank = 1;
                    for (UUID u : uuids) {
                        ps.setString(1, u.toString());
                        ps.setString(2, names.getOrDefault(u, u.toString()));
                        ps.setInt(3, rank++);
                        ps.setLong(4, now);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                connection.commit();
            } catch (SQLException e) {
                try { connection.rollback(); } catch (SQLException ignored) {}
                plugin.getLogger().warning("saveCouncilSeats failed: " + e.getMessage());
            } finally {
                try { connection.setAutoCommit(true); } catch (SQLException ignored) {}
            }
        });
    }

    // ---------- Proposals ----------

    public CompletableFuture<Integer> createProposalAsync(Proposal.Type type, UUID proposer, String target,
                                                          String value, String reason, long expires) {
        return supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO proposals (type, proposer, target, value, reason, created_at, expires_at, status) "
                            + "VALUES (?,?,?,?,?,?,?, 'ACTIVE')", Statement.RETURN_GENERATED_KEYS)) {
                long now = System.currentTimeMillis();
                ps.setString(1, type.name());
                ps.setString(2, proposer.toString());
                ps.setString(3, target);
                ps.setString(4, value);
                ps.setString(5, reason);
                ps.setLong(6, now);
                ps.setLong(7, expires);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) return keys.getInt(1);
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("createProposal failed: " + e.getMessage());
            }
            return -1;
        });
    }

    public CompletableFuture<Proposal> getProposalAsync(int id) {
        return supplyAsync(() -> loadProposal(id));
    }

    public CompletableFuture<List<Proposal>> getActiveProposalsAsync() {
        return supplyAsync(() -> {
            List<Proposal> list = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id FROM proposals WHERE status = 'ACTIVE' AND expires_at > ? ORDER BY id")) {
                ps.setLong(1, System.currentTimeMillis());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Proposal p = loadProposal(rs.getInt("id"));
                        if (p != null) list.add(p);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("getActiveProposals failed: " + e.getMessage());
            }
            return list;
        });
    }

    private Proposal loadProposal(int id) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM proposals WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Proposal.Type type = Proposal.Type.valueOf(rs.getString("type"));
                UUID proposer = UUID.fromString(rs.getString("proposer"));
                Proposal p = new Proposal(id, type, proposer, rs.getString("target"), rs.getString("value"),
                        rs.getString("reason"), rs.getLong("created_at"), rs.getLong("expires_at"),
                        rs.getString("status"), rs.getString("discord_thread_id"));
                // load votes
                try (PreparedStatement vps = connection.prepareStatement(
                        "SELECT voter, yes FROM votes WHERE proposal_id = ?")) {
                    vps.setInt(1, id);
                    try (ResultSet vrs = vps.executeQuery()) {
                        while (vrs.next()) {
                            p.addVote(UUID.fromString(vrs.getString("voter")), vrs.getInt("yes") == 1);
                        }
                    }
                }
                return p;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("loadProposal failed: " + e.getMessage());
            return null;
        }
    }

    public void saveVote(int proposalId, UUID voter, boolean yes) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO votes (proposal_id, voter, yes, voted_at) VALUES (?,?,?,?)")) {
                ps.setInt(1, proposalId);
                ps.setString(2, voter.toString());
                ps.setInt(3, yes ? 1 : 0);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("saveVote failed: " + e.getMessage());
            }
        });
    }

    public void markProposalExecuted(int id) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE proposals SET status = 'EXECUTED' WHERE id = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("markProposalExecuted failed: " + e.getMessage());
            }
        });
    }

    public void markProposalCancelled(int id) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE proposals SET status = 'CANCELLED' WHERE id = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("markProposalCancelled failed: " + e.getMessage());
            }
        });
    }

    public void setDiscordThreadId(int proposalId, String threadId) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE proposals SET discord_thread_id = ? WHERE id = ?")) {
                ps.setString(1, threadId);
                ps.setInt(2, proposalId);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("setDiscordThreadId failed: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<Proposal> findActivePardonForTargetAsync(String targetName) {
        return supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id FROM proposals WHERE status = 'ACTIVE' AND expires_at > ? "
                            + "AND type IN ('PARDON','REPARDON') AND lower(target) = lower(?) ORDER BY id DESC LIMIT 1")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, targetName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return loadProposal(rs.getInt("id"));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("findActivePardon failed: " + e.getMessage());
            }
            return null;
        });
    }

    // ---------- Ban ladder / cooldown ----------

    public void setBanProposeCooldown(UUID uuid, long untilMs) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO ban_propose_cooldown (uuid, until_ms) VALUES (?,?)")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, untilMs);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("setBanProposeCooldown failed: " + e.getMessage());
            }
        });
    }

    // ---------- Pending plugin actions ----------

    public void setPendingPluginAction(String name, boolean enable) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO pending_plugin_actions (plugin_name, enable) VALUES (?,?)")) {
                ps.setString(1, name);
                ps.setInt(2, enable ? 1 : 0);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("setPendingPluginAction failed: " + e.getMessage());
            }
        });
    }

    public Map<String, Boolean> getPendingPluginActionsSync() {
        Map<String, Boolean> map = new HashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT plugin_name, enable FROM pending_plugin_actions")) {
            while (rs.next()) map.put(rs.getString("plugin_name"), rs.getInt("enable") == 1);
        } catch (SQLException e) {
            plugin.getLogger().warning("getPendingPluginActions failed: " + e.getMessage());
        }
        return map;
    }

    public void clearPendingPluginActions() {
        runAsync(() -> {
            try (Statement st = connection.createStatement()) {
                st.execute("DELETE FROM pending_plugin_actions");
            } catch (SQLException e) {
                plugin.getLogger().warning("clearPendingPluginActions failed: " + e.getMessage());
            }
        });
    }

    // ---------- Tracked bans (ban review system) ----------

    public record TrackedBan(
            int id,
            UUID targetUuid,
            String targetName,
            String reason,
            String source,
            UUID bannedByUuid,
            String bannedByName,
            long bannedAt,
            Long firstPromptedAt,
            boolean active
    ) {}

    public CompletableFuture<Integer> recordTrackedBanAsync(
            UUID targetUuid, String targetName, String reason, String source,
            UUID bannedByUuid, String bannedByName, long bannedAt) {
        return supplyAsync(() -> {
            // Dedupe active row for same target
            try (PreparedStatement check = connection.prepareStatement(
                    "SELECT id FROM tracked_bans WHERE target_uuid = ? AND active = 1 ORDER BY id DESC LIMIT 1")) {
                check.setString(1, targetUuid.toString());
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) return rs.getInt("id");
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("recordTrackedBan check failed: " + e.getMessage());
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO tracked_bans (target_uuid, target_name, reason, source, banned_by_uuid, "
                            + "banned_by_name, banned_at, active) VALUES (?,?,?,?,?,?,?,1)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, targetUuid.toString());
                ps.setString(2, targetName);
                ps.setString(3, reason);
                ps.setString(4, source);
                ps.setString(5, bannedByUuid != null ? bannedByUuid.toString() : null);
                ps.setString(6, bannedByName);
                ps.setLong(7, bannedAt);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) return keys.getInt(1);
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("recordTrackedBan insert failed: " + e.getMessage());
            }
            return -1;
        });
    }

    public void markTrackedBanInactive(UUID targetUuid) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE tracked_bans SET active = 0 WHERE target_uuid = ? AND active = 1")) {
                ps.setString(1, targetUuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("markTrackedBanInactive failed: " + e.getMessage());
            }
        });
    }

    public void markTrackedBanInactiveByName(String targetName) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE tracked_bans SET active = 0 WHERE lower(target_name) = lower(?) AND active = 1")) {
                ps.setString(1, targetName);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("markTrackedBanInactiveByName failed: " + e.getMessage());
            }
        });
    }

    public void setBanFirstPrompted(int banId, long when) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE tracked_bans SET first_prompted_at = ? WHERE id = ? AND first_prompted_at IS NULL")) {
                ps.setLong(1, when);
                ps.setInt(2, banId);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("setBanFirstPrompted failed: " + e.getMessage());
            }
        });
    }

    public void saveBanReviewResponse(int banId, UUID councilUuid, String response) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO ban_review_responses (ban_id, council_uuid, response, responded_at) "
                            + "VALUES (?,?,?,?)")) {
                ps.setInt(1, banId);
                ps.setString(2, councilUuid.toString());
                ps.setString(3, response);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("saveBanReviewResponse failed: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<List<TrackedBan>> getPendingReviewsForCouncilAsync(
            UUID councilUuid, long lookbackMs, long expireMs, int max) {
        return supplyAsync(() -> {
            List<TrackedBan> list = new ArrayList<>();
            long after = System.currentTimeMillis() - lookbackMs;
            long expireBefore = System.currentTimeMillis() - expireMs;
            try (PreparedStatement ps = connection.prepareStatement("""
                SELECT b.* FROM tracked_bans b
                WHERE b.active = 1
                  AND b.banned_at >= ?
                  AND (b.first_prompted_at IS NULL OR b.first_prompted_at >= ?)
                  AND NOT EXISTS (
                    SELECT 1 FROM ban_review_responses r
                    WHERE r.ban_id = b.id AND r.council_uuid = ?
                  )
                ORDER BY b.banned_at DESC
                LIMIT ?
                """)) {
                ps.setLong(1, after);
                ps.setLong(2, expireBefore);
                ps.setString(3, councilUuid.toString());
                ps.setInt(4, Math.max(1, max * 3)); // fetch extra, filter in manager
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(rowToTrackedBan(rs));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("getPendingReviews failed: " + e.getMessage());
            }
            return list;
        });
    }

    public CompletableFuture<TrackedBan> getTrackedBanAsync(int id) {
        return supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM tracked_bans WHERE id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rowToTrackedBan(rs);
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("getTrackedBan failed: " + e.getMessage());
            }
            return null;
        });
    }

    public CompletableFuture<Boolean> hasBanReviewResponseAsync(int banId, UUID councilUuid) {
        return supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM ban_review_responses WHERE ban_id = ? AND council_uuid = ?")) {
                ps.setInt(1, banId);
                ps.setString(2, councilUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("hasBanReviewResponse failed: " + e.getMessage());
            }
            return false;
        });
    }

    private TrackedBan rowToTrackedBan(ResultSet rs) throws SQLException {
        String byUuid = rs.getString("banned_by_uuid");
        long first = rs.getLong("first_prompted_at");
        boolean firstNull = rs.wasNull();
        return new TrackedBan(
                rs.getInt("id"),
                UUID.fromString(rs.getString("target_uuid")),
                rs.getString("target_name"),
                rs.getString("reason"),
                rs.getString("source"),
                byUuid != null ? UUID.fromString(byUuid) : null,
                rs.getString("banned_by_name"),
                rs.getLong("banned_at"),
                firstNull ? null : first,
                rs.getInt("active") == 1
        );
    }

    private static String encodeStats(Map<String, Long> map) {
        StringBuilder sb = new StringBuilder();
        for (var e : map.entrySet()) {
            if (sb.length() > 0) sb.append('|');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
