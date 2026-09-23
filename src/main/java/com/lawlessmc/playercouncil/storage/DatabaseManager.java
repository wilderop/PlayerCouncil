package com.lawlessmc.playercouncil.storage;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import com.lawlessmc.playercouncil.models.ActivitySnapshot;
import com.lawlessmc.playercouncil.models.BugReport;
import com.lawlessmc.playercouncil.models.Proposal;
import com.lawlessmc.playercouncil.util.CoordRedact;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** All SQLite access is serialized on a single background thread. */
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
        runSync(() -> {
            try {
                File dbFile = new File(plugin.getDataFolder(), "playercouncil.db");
                if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
                connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
                try (Statement st = connection.createStatement()) {
                    st.execute("PRAGMA journal_mode=WAL");
                    st.execute("PRAGMA busy_timeout=5000");
                    st.execute("PRAGMA synchronous=NORMAL");
                }
                createTables();
                pruneOldSnapshotsInternal(30);
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize SQLite: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void createTables() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS snapshots (uuid TEXT NOT NULL, timestamp INTEGER NOT NULL, playtime INTEGER NOT NULL, walk INTEGER NOT NULL, fly INTEGER NOT NULL, mob_kills INTEGER NOT NULL, PRIMARY KEY (uuid, timestamp))");
            st.execute("CREATE TABLE IF NOT EXISTS player_meta (uuid TEXT PRIMARY KEY, name TEXT, first_join INTEGER, total_playtime INTEGER DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS proposals (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, proposer TEXT NOT NULL, target TEXT NOT NULL, value TEXT, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, cancelled INTEGER DEFAULT 0, executed INTEGER DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS votes (proposal_id INTEGER NOT NULL, voter TEXT NOT NULL, yes INTEGER NOT NULL, PRIMARY KEY (proposal_id, voter))");
            st.execute("CREATE TABLE IF NOT EXISTS council (uuid TEXT PRIMARY KEY, name TEXT, rank INTEGER)");
            st.execute("CREATE TABLE IF NOT EXISTS audit_log (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, message TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS pending_plugin_actions (plugin_name TEXT PRIMARY KEY, enable INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS ban_ladder (uuid TEXT PRIMARY KEY, name TEXT, stage INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS snapshot_stats (uuid TEXT NOT NULL, timestamp INTEGER NOT NULL, stat TEXT NOT NULL, value INTEGER NOT NULL, PRIMARY KEY (uuid, timestamp, stat))");
            st.execute("CREATE TABLE IF NOT EXISTS scoreboard_optin (uuid TEXT PRIMARY KEY, enabled INTEGER NOT NULL DEFAULT 1)");
            st.execute("CREATE TABLE IF NOT EXISTS ban_propose_cooldown (uuid TEXT PRIMARY KEY, until_ms BIGINT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS player_ips (uuid TEXT NOT NULL, ip TEXT NOT NULL, first_seen BIGINT, last_seen BIGINT, PRIMARY KEY (uuid, ip))");
            st.execute("CREATE TABLE IF NOT EXISTS tracked_bans (id INTEGER PRIMARY KEY AUTOINCREMENT, target_uuid TEXT NOT NULL, target_name TEXT NOT NULL, reason TEXT, source TEXT, banned_by_uuid TEXT, banned_by_name TEXT, banned_at BIGINT NOT NULL, first_prompted_at BIGINT, active INTEGER NOT NULL DEFAULT 1)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_tracked_bans_active ON tracked_bans(active, banned_at)");
            st.execute("CREATE TABLE IF NOT EXISTS ban_review_responses (ban_id INTEGER NOT NULL, council_uuid TEXT NOT NULL, response TEXT NOT NULL, responded_at BIGINT NOT NULL, PRIMARY KEY (ban_id, council_uuid))");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS bugs (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      reporter_uuid TEXT NOT NULL,
                      reporter_name TEXT NOT NULL,
                      title TEXT NOT NULL,
                      title_norm TEXT NOT NULL,
                      status TEXT NOT NULL DEFAULT 'open',
                      created_at INTEGER NOT NULL,
                      updated_at INTEGER NOT NULL,
                      closed_by_uuid TEXT,
                      closed_by_name TEXT,
                      close_note TEXT,
                      report_count INTEGER NOT NULL DEFAULT 1,
                      location TEXT,
                      question TEXT,
                      last_nightly_at INTEGER,
                      last_player_reply_at INTEGER
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_bugs_status ON bugs(status, updated_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_bugs_norm ON bugs(title_norm, status)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_bugs_reporter ON bugs(reporter_uuid, created_at)");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS bug_events (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      bug_id INTEGER NOT NULL,
                      at INTEGER NOT NULL,
                      kind TEXT NOT NULL,
                      actor_uuid TEXT,
                      actor_name TEXT,
                      text TEXT
                    )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_bug_events_bug ON bug_events(bug_id, id)");
            st.execute("CREATE TABLE IF NOT EXISTS bug_plusones (bug_id INTEGER NOT NULL, uuid TEXT NOT NULL, PRIMARY KEY (bug_id, uuid))");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS bug_blocks (
                      uuid TEXT PRIMARY KEY,
                      name TEXT,
                      reason TEXT,
                      by_uuid TEXT,
                      by_name TEXT,
                      at INTEGER NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS bug_reporters (
                      uuid TEXT PRIMARY KEY,
                      name TEXT,
                      false_closes INTEGER NOT NULL DEFAULT 0
                    )""");
            // Migrations for tables created by older plugin versions
            try { st.execute("ALTER TABLE proposals ADD COLUMN reason TEXT"); } catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE proposals ADD COLUMN discord_thread_id TEXT"); } catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE player_ips ADD COLUMN first_seen INTEGER"); } catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE player_ips ADD COLUMN last_seen INTEGER"); } catch (SQLException ignored) {}
            st.execute("""
                    CREATE TABLE IF NOT EXISTS discord_links (
                      uuid TEXT PRIMARY KEY,
                      discord_id TEXT NOT NULL UNIQUE,
                      discord_name TEXT,
                      keep INTEGER NOT NULL DEFAULT 0,
                      linked_at INTEGER NOT NULL
                    )""");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_discord_links_discord_id ON discord_links(discord_id)");
            try { st.execute("UPDATE bugs SET location = NULL WHERE location IS NOT NULL AND TRIM(location) != ''"); } catch (SQLException ignored) {}
            migrateLegacySnapshots(st);
        }
    }

    private void migrateLegacySnapshots(Statement st) throws SQLException {
        try {
            var rs = st.executeQuery("PRAGMA table_info(snapshots)");
            boolean hasPlaytime = false;
            while (rs.next()) {
                if ("playtime".equalsIgnoreCase(rs.getString("name"))) { hasPlaytime = true; break; }
            }
            if (!hasPlaytime) return;
            st.execute("INSERT OR IGNORE INTO snapshot_stats (uuid, timestamp, stat, value) SELECT uuid, timestamp, 'PLAY_ONE_MINUTE', playtime FROM snapshots WHERE playtime IS NOT NULL");
            st.execute("INSERT OR IGNORE INTO snapshot_stats (uuid, timestamp, stat, value) SELECT uuid, timestamp, 'WALK_ONE_CM', walk FROM snapshots WHERE walk IS NOT NULL");
            st.execute("INSERT OR IGNORE INTO snapshot_stats (uuid, timestamp, stat, value) SELECT uuid, timestamp, 'AVIATE_ONE_CM', fly FROM snapshots WHERE fly IS NOT NULL");
            st.execute("INSERT OR IGNORE INTO snapshot_stats (uuid, timestamp, stat, value) SELECT uuid, timestamp, 'MOB_KILLS', mob_kills FROM snapshots WHERE mob_kills IS NOT NULL");
        } catch (SQLException e) {
            plugin.getLogger().info("Legacy snapshot migration skipped: " + e.getMessage());
        }
    }

    public void runSync(Runnable work) {
        try { dbExecutor.submit(work).get(30, TimeUnit.SECONDS); }
        catch (Exception e) { plugin.getLogger().severe("DB sync task failed: " + e.getMessage()); e.printStackTrace(); }
    }

    public void runAsync(Runnable work) {
        dbExecutor.execute(() -> {
            try { work.run(); } catch (Exception e) {
                plugin.getLogger().warning("DB async task failed: " + e.getMessage()); e.printStackTrace();
            }
        });
    }

    public <T> CompletableFuture<T> supplyAsync(Callable<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try { return work.call(); }
            catch (Exception e) { throw new CompletionException(e); }
        }, dbExecutor);
    }

    public void close() {
        runSync(() -> {
            try { if (connection != null && !connection.isClosed()) connection.close(); }
            catch (SQLException e) { e.printStackTrace(); }
        });
        dbExecutor.shutdown();
        try { dbExecutor.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public void pruneOldSnapshots(int days) { runAsync(() -> pruneOldSnapshotsInternal(days)); }

    private void pruneOldSnapshotsInternal(int days) {
        long cutoff = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000);
        try {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM snapshot_stats WHERE timestamp < ?")) {
                ps.setLong(1, cutoff); ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM snapshots WHERE timestamp < ?")) {
                ps.setLong(1, cutoff);
                int removed = ps.executeUpdate();
                if (removed > 0) plugin.getLogger().info("Pruned " + removed + " activity snapshots older than " + days + " days.");
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public void saveSnapshot(ActivitySnapshot snap) {
        runAsync(() -> saveSnapshotInternal(snap, true));
    }

    public void applyFabricDeltas(UUID uuid, String name, long timestamp, Map<String, Long> deltas) {
        runAsync(() -> {
            ActivitySnapshot latest = latestSnapshotInternal(uuid);
            Map<String, Long> values = new LinkedHashMap<>();
            if (latest != null) {
                values.putAll(latest.getValues());
            }
            for (var e : deltas.entrySet()) {
                if (e.getValue() == null || e.getValue() <= 0) continue;
                values.put(e.getKey(), values.getOrDefault(e.getKey(), 0L) + e.getValue());
            }
            if (values.isEmpty()) return;
            saveSnapshotInternal(new ActivitySnapshot(uuid, timestamp, values), false);
            long play = values.getOrDefault("PLAY_ONE_MINUTE", 0L);
            if (name != null && !name.isBlank()) {
                upsertPlayerMetaInternal(uuid, name, 0L, play);
            }
        });
    }

    private void saveSnapshotInternal(ActivitySnapshot snap, boolean skipIfOlder) {
        try {
            if (skipIfOlder) {
                ActivitySnapshot latest = latestSnapshotInternal(snap.getUuid());
                if (latest != null && snap.getPlaytime() + 20 < latest.getPlaytime()) {
                    return;
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO snapshots (uuid, timestamp, playtime, walk, fly, mob_kills) VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, snap.getUuid().toString());
                ps.setLong(2, snap.getTimestamp());
                ps.setLong(3, snap.get("PLAY_ONE_MINUTE"));
                ps.setLong(4, snap.get("WALK_ONE_CM"));
                ps.setLong(5, snap.get("AVIATE_ONE_CM"));
                ps.setLong(6, snap.get("MOB_KILLS"));
                ps.executeUpdate();
            }
            String sql = "INSERT OR REPLACE INTO snapshot_stats (uuid, timestamp, stat, value) VALUES (?,?,?,?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (var e : snap.getValues().entrySet()) {
                    ps.setString(1, snap.getUuid().toString());
                    ps.setLong(2, snap.getTimestamp());
                    ps.setString(3, e.getKey());
                    ps.setLong(4, e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private ActivitySnapshot latestSnapshotInternal(UUID uuid) {
        Long ts = null;
        try (PreparedStatement ps = connection.prepareStatement("SELECT MAX(timestamp) FROM snapshot_stats WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                long v = rs.getLong(1);
                if (!rs.wasNull()) ts = v;
            }
        } catch (SQLException e) { e.printStackTrace(); }
        if (ts == null) return null;
        return loadSnapshotInternal(uuid, ts);
    }

    public CompletableFuture<List<ActivitySnapshot>> getSnapshotsSinceAsync(UUID uuid, long sinceTimestamp) {
        return supplyAsync(() -> {
            List<Long> times = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT DISTINCT timestamp FROM snapshot_stats WHERE uuid = ? AND timestamp >= ? ORDER BY timestamp ASC")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, sinceTimestamp);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) times.add(rs.getLong(1));
            } catch (SQLException e) { e.printStackTrace(); }
            if (times.isEmpty()) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT timestamp FROM snapshots WHERE uuid = ? AND timestamp >= ? ORDER BY timestamp ASC")) {
                    ps.setString(1, uuid.toString());
                    ps.setLong(2, sinceTimestamp);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) times.add(rs.getLong(1));
                } catch (SQLException e) { e.printStackTrace(); }
            }
            List<ActivitySnapshot> list = new ArrayList<>();
            for (long ts : times) list.add(loadSnapshotInternal(uuid, ts));
            return list;
        });
    }

    public CompletableFuture<ActivitySnapshot> getLatestSnapshotAsync(UUID uuid) {
        return supplyAsync(() -> {
            Long ts = null;
            try (PreparedStatement ps = connection.prepareStatement("SELECT MAX(timestamp) FROM snapshot_stats WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) { long v = rs.getLong(1); if (!rs.wasNull()) ts = v; }
            } catch (SQLException e) { e.printStackTrace(); }
            if (ts == null) {
                try (PreparedStatement ps = connection.prepareStatement("SELECT MAX(timestamp) FROM snapshots WHERE uuid = ?")) {
                    ps.setString(1, uuid.toString());
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) { long v = rs.getLong(1); if (!rs.wasNull()) ts = v; }
                } catch (SQLException e) { e.printStackTrace(); }
            }
            if (ts == null) return null;
            return loadSnapshotInternal(uuid, ts);
        });
    }

    private ActivitySnapshot loadSnapshotInternal(UUID uuid, long timestamp) {
        Map<String, Long> values = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT stat, value FROM snapshot_stats WHERE uuid = ? AND timestamp = ?")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, timestamp);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) values.put(rs.getString("stat"), rs.getLong("value"));
        } catch (SQLException e) { e.printStackTrace(); }
        if (values.isEmpty()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT playtime, walk, fly, mob_kills FROM snapshots WHERE uuid = ? AND timestamp = ?")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, timestamp);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    values.put("PLAY_ONE_MINUTE", rs.getLong("playtime"));
                    values.put("WALK_ONE_CM", rs.getLong("walk"));
                    values.put("AVIATE_ONE_CM", rs.getLong("fly"));
                    values.put("MOB_KILLS", rs.getLong("mob_kills"));
                }
            } catch (SQLException e) { e.printStackTrace(); }
        }
        return new ActivitySnapshot(uuid, timestamp, values);
    }

    public void upsertPlayerMeta(UUID uuid, String name, long firstJoin, long totalPlaytime) {
        runAsync(() -> upsertPlayerMetaInternal(uuid, name, firstJoin, totalPlaytime));
    }

    private void upsertPlayerMetaInternal(UUID uuid, String name, long firstJoin, long totalPlaytime) {
        String sql = "INSERT INTO player_meta (uuid, name, first_join, total_playtime) VALUES (?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, total_playtime = excluded.total_playtime";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString()); ps.setString(2, name);
            ps.setLong(3, firstJoin); ps.setLong(4, totalPlaytime);
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public CompletableFuture<Long> getFirstJoinAsync(UUID uuid) {
        return supplyAsync(() -> getMetaLongInternal(uuid, "first_join"));
    }

    public CompletableFuture<Long> getTotalPlaytimeAsync(UUID uuid) {
        return supplyAsync(() -> getMetaLongInternal(uuid, "total_playtime"));
    }

    private long getMetaLongInternal(UUID uuid, String column) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT " + column + " FROM player_meta WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getLong(1);
        } catch (SQLException e) { e.printStackTrace(); }
        return 0;
    }

    public CompletableFuture<Map<UUID, String>> getAllKnownPlayersAsync() {
        return supplyAsync(() -> {
            Map<UUID, String> map = new HashMap<>();
            try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid, name FROM player_meta")) {
                while (rs.next()) map.put(UUID.fromString(rs.getString("uuid")), rs.getString("name"));
            } catch (SQLException e) { e.printStackTrace(); }
            return map;
        });
    }

    public void setCouncil(List<Map.Entry<UUID, String>> members) {
        runAsync(() -> {
            try (Statement st = connection.createStatement()) { st.execute("DELETE FROM council"); }
            catch (SQLException e) { e.printStackTrace(); return; }
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO council (uuid, name, rank) VALUES (?, ?, ?)")) {
                int rank = 1;
                for (Map.Entry<UUID, String> e : members) {
                    ps.setString(1, e.getKey().toString()); ps.setString(2, e.getValue()); ps.setInt(3, rank++); ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public CompletableFuture<List<UUID>> getCouncilUuidsAsync() {
        return supplyAsync(() -> {
            List<UUID> list = new ArrayList<>();
            try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid FROM council ORDER BY rank ASC")) {
                while (rs.next()) list.add(UUID.fromString(rs.getString("uuid")));
            } catch (SQLException e) { e.printStackTrace(); }
            return list;
        });
    }

    public void removeCouncilMember(UUID uuid) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM council WHERE uuid = ?")) {
                ps.setString(1, uuid.toString()); ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public record DiscordLink(UUID uuid, String discordId, String discordName, boolean keep, long linkedAt) {}

    public CompletableFuture<DiscordLink> getDiscordLinkByUuidAsync(UUID uuid) {
        return supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT uuid, discord_id, discord_name, keep, linked_at FROM discord_links WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rowToDiscordLink(rs);
            } catch (SQLException e) { e.printStackTrace(); }
            return null;
        });
    }

    public CompletableFuture<DiscordLink> getDiscordLinkByDiscordIdAsync(String discordId) {
        return supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT uuid, discord_id, discord_name, keep, linked_at FROM discord_links WHERE discord_id = ?")) {
                ps.setString(1, discordId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rowToDiscordLink(rs);
            } catch (SQLException e) { e.printStackTrace(); }
            return null;
        });
    }

    public CompletableFuture<List<DiscordLink>> getAllDiscordLinksAsync() {
        return supplyAsync(() -> {
            List<DiscordLink> list = new ArrayList<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT uuid, discord_id, discord_name, keep, linked_at FROM discord_links")) {
                while (rs.next()) list.add(rowToDiscordLink(rs));
            } catch (SQLException e) { e.printStackTrace(); }
            return list;
        });
    }

    /**
     * @return null on success, otherwise a short error for the player
     */
    public CompletableFuture<String> putDiscordLinkAsync(UUID uuid, String discordId, String discordName, boolean keep) {
        return supplyAsync(() -> {
            try (PreparedStatement other = connection.prepareStatement(
                    "SELECT uuid FROM discord_links WHERE discord_id = ? AND uuid != ?")) {
                other.setString(1, discordId);
                other.setString(2, uuid.toString());
                ResultSet rs = other.executeQuery();
                if (rs.next()) return "That Discord account is already linked to another Minecraft account.";
            } catch (SQLException e) {
                e.printStackTrace();
                return "Database error.";
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO discord_links (uuid, discord_id, discord_name, keep, linked_at) VALUES (?,?,?,?,?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET discord_id = excluded.discord_id, "
                            + "discord_name = excluded.discord_name, keep = excluded.keep, linked_at = excluded.linked_at")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, discordId);
                ps.setString(3, discordName);
                ps.setInt(4, keep ? 1 : 0);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
                return "Database error.";
            }
            return null;
        });
    }

    public CompletableFuture<Boolean> deleteDiscordLinkAsync(UUID uuid) {
        return supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM discord_links WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
        });
    }

    private DiscordLink rowToDiscordLink(ResultSet rs) throws SQLException {
        return new DiscordLink(
                UUID.fromString(rs.getString("uuid")),
                rs.getString("discord_id"),
                rs.getString("discord_name"),
                rs.getInt("keep") != 0,
                rs.getLong("linked_at"));
    }

    public CompletableFuture<Integer> createProposalAsync(Proposal.Type type, UUID proposer, String target, String value, long expiresAt) {
        return createProposalAsync(type, proposer, target, value, null, expiresAt);
    }

    public CompletableFuture<Integer> createProposalAsync(Proposal.Type type, UUID proposer, String target, String value, String reason, long expiresAt) {
        return supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO proposals (type, proposer, target, value, created_at, expires_at, reason) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, type.name()); ps.setString(2, proposer.toString());
                ps.setString(3, target); ps.setString(4, value);
                ps.setLong(5, System.currentTimeMillis()); ps.setLong(6, expiresAt);
                ps.setString(7, reason);
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                if (keys.next()) return keys.getInt(1);
            } catch (SQLException e) { e.printStackTrace(); }
            return -1;
        });
    }

    public void saveVote(int proposalId, UUID voter, boolean yes) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO votes (proposal_id, voter, yes) VALUES (?, ?, ?)")) {
                ps.setInt(1, proposalId); ps.setString(2, voter.toString()); ps.setInt(3, yes ? 1 : 0);
                ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public void markProposalCancelled(int id) { updateProposalFlag(id, "cancelled", 1); }
    public void markProposalExecuted(int id) { updateProposalFlag(id, "executed", 1); }

    private void updateProposalFlag(int id, String column, int value) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("UPDATE proposals SET " + column + " = ? WHERE id = ?")) {
                ps.setInt(1, value); ps.setInt(2, id); ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public CompletableFuture<List<Proposal>> getActiveProposalsAsync() {
        return supplyAsync(() -> {
            List<Proposal> list = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM proposals WHERE cancelled = 0 AND executed = 0 AND expires_at > ?")) {
                ps.setLong(1, System.currentTimeMillis());
                ResultSet rs = ps.executeQuery();
                while (rs.next()) { Proposal p = rowToProposal(rs); loadVotesInternal(p); list.add(p); }
            } catch (SQLException e) { e.printStackTrace(); }
            return list;
        });
    }

    public CompletableFuture<Proposal> getProposalAsync(int id) {
        return supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM proposals WHERE id = ?")) {
                ps.setInt(1, id);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) { Proposal p = rowToProposal(rs); loadVotesInternal(p); return p; }
            } catch (SQLException e) { e.printStackTrace(); }
            return null;
        });
    }

    private Proposal rowToProposal(ResultSet rs) throws SQLException {
        Proposal p = new Proposal(rs.getInt("id"), Proposal.Type.valueOf(rs.getString("type")),
                UUID.fromString(rs.getString("proposer")), rs.getString("target"), rs.getString("value"),
                rs.getLong("created_at"), rs.getLong("expires_at"));
        p.setCancelled(rs.getInt("cancelled") == 1);
        p.setExecuted(rs.getInt("executed") == 1);
        try {
            String tid = rs.getString("discord_thread_id");
            if (tid != null && !tid.isBlank()) p.setDiscordThreadId(tid);
        } catch (SQLException ignored) {}
        try {
            String reason = rs.getString("reason");
            if (reason != null && !reason.isBlank()) p.setReason(reason);
        } catch (SQLException ignored) {}
        return p;
    }

    private void loadVotesInternal(Proposal p) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT voter, yes FROM votes WHERE proposal_id = ?")) {
            ps.setInt(1, p.getId());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) p.addVote(UUID.fromString(rs.getString("voter")), rs.getInt("yes") == 1);
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public void setDiscordThreadId(int proposalId, String threadId) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("UPDATE proposals SET discord_thread_id = ? WHERE id = ?")) {
                ps.setString(1, threadId); ps.setInt(2, proposalId); ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public void log(String message) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO audit_log (timestamp, message) VALUES (?, ?)")) {
                ps.setLong(1, System.currentTimeMillis()); ps.setString(2, message); ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public CompletableFuture<List<String>> getRecentAuditAsync(int limit) {
        return supplyAsync(() -> {
            List<String> list = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement("SELECT message FROM audit_log ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, limit);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) list.add(rs.getString("message"));
            } catch (SQLException e) { e.printStackTrace(); }
            return list;
        });
    }

    public void setPendingPluginAction(String pluginName, boolean enable) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO pending_plugin_actions (plugin_name, enable) VALUES (?, ?)")) {
                ps.setString(1, pluginName); ps.setInt(2, enable ? 1 : 0); ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public Map<String, Boolean> getPendingPluginActionsSync() {
        Map<String, Boolean> map = new ConcurrentHashMap<>();
        runSync(() -> {
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT plugin_name, enable FROM pending_plugin_actions")) {
                while (rs.next()) map.put(rs.getString("plugin_name"), rs.getInt("enable") == 1);
            } catch (SQLException e) { e.printStackTrace(); }
        });
        return map;
    }

    public void clearPendingPluginActions() {
        runAsync(() -> {
            try (Statement st = connection.createStatement()) { st.execute("DELETE FROM pending_plugin_actions"); }
            catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public CompletableFuture<Integer> getBanLadderStageAsync(UUID uuid) {
        return supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT stage FROM ban_ladder WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rs.getInt("stage");
            } catch (SQLException e) { e.printStackTrace(); }
            return 0;
        });
    }

    public void setBanLadderStage(UUID uuid, String name, int stage) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ban_ladder (uuid, name, stage) VALUES (?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, stage = excluded.stage")) {
                ps.setString(1, uuid.toString()); ps.setString(2, name); ps.setInt(3, stage); ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public void setScoreboardOptIn(UUID uuid, boolean on) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO scoreboard_optin (uuid, enabled) VALUES (?, ?)")) {
                ps.setString(1, uuid.toString()); ps.setInt(2, on ? 1 : 0); ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public CompletableFuture<Boolean> isScoreboardOptInAsync(UUID uuid) {
        return supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT enabled FROM scoreboard_optin WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rs.getInt("enabled") == 1;
            } catch (SQLException e) { e.printStackTrace(); }
            return false;
        });
    }

    public CompletableFuture<java.util.Set<UUID>> loadScoreboardOptInsAsync() {
        return supplyAsync(() -> {
            java.util.Set<UUID> set = new java.util.HashSet<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT uuid FROM scoreboard_optin WHERE enabled = 1")) {
                while (rs.next()) set.add(UUID.fromString(rs.getString("uuid")));
            } catch (SQLException e) { e.printStackTrace(); }
            return set;
        });
    }

    public void setBanProposeCooldown(UUID uuid, long untilMs) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO ban_propose_cooldown (uuid, until_ms) VALUES (?, ?)")) {
                ps.setString(1, uuid.toString()); ps.setLong(2, untilMs); ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public CompletableFuture<Long> getBanProposeCooldownAsync(UUID uuid) {
        return supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT until_ms FROM ban_propose_cooldown WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rs.getLong("until_ms");
            } catch (SQLException e) { e.printStackTrace(); }
            return 0L;
        });
    }

    public void recordPlayerIp(UUID uuid, String ip) {
        if (ip == null || ip.isBlank()) return;
        String clean = ip.startsWith("/") ? ip.substring(1) : ip;
        long now = System.currentTimeMillis();
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO player_ips (uuid, ip, first_seen, last_seen) VALUES (?,?,?,?) ON CONFLICT(uuid, ip) DO UPDATE SET last_seen = excluded.last_seen")) {
                ps.setString(1, uuid.toString()); ps.setString(2, clean);
                ps.setLong(3, now); ps.setLong(4, now); ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public CompletableFuture<Map<UUID, Set<UUID>>> getIpRelatedGroupsAsync(Collection<UUID> candidates) {
        return supplyAsync(() -> {
            Map<UUID, Set<UUID>> groups = new HashMap<>();
            if (candidates == null || candidates.isEmpty()) return groups;
            try {
                Map<String, Set<UUID>> ipToPlayers = new HashMap<>();
                for (UUID u : candidates) {
                    try (PreparedStatement ps = connection.prepareStatement("SELECT ip FROM player_ips WHERE uuid = ?")) {
                        ps.setString(1, u.toString());
                        ResultSet rs = ps.executeQuery();
                        while (rs.next()) {
                            ipToPlayers.computeIfAbsent(rs.getString("ip"), k -> new HashSet<>()).add(u);
                        }
                    }
                }
                for (Set<UUID> group : ipToPlayers.values()) {
                    if (group.size() < 2) continue;
                    for (UUID u : group) {
                        groups.computeIfAbsent(u, k -> new HashSet<>()).addAll(group);
                        groups.get(u).remove(u);
                    }
                }
            } catch (SQLException e) { e.printStackTrace(); }
            return groups;
        });
    }

    public record TrackedBan(
            int id, UUID targetUuid, String targetName, String reason, String source,
            UUID bannedByUuid, String bannedByName, long bannedAt, Long firstPromptedAt, boolean active) {}

    public CompletableFuture<Integer> recordTrackedBanAsync(
            UUID targetUuid, String targetName, String reason, String source,
            UUID bannedByUuid, String bannedByName, long bannedAt) {
        return supplyAsync(() -> {
            try (PreparedStatement check = connection.prepareStatement(
                    "SELECT id FROM tracked_bans WHERE target_uuid = ? AND active = 1 ORDER BY id DESC LIMIT 1")) {
                check.setString(1, targetUuid.toString());
                ResultSet rs = check.executeQuery();
                if (rs.next()) return rs.getInt("id");
            } catch (SQLException e) { e.printStackTrace(); }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO tracked_bans (target_uuid, target_name, reason, source, banned_by_uuid, banned_by_name, banned_at, active) VALUES (?,?,?,?,?,?,?,1)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, targetUuid.toString());
                ps.setString(2, targetName);
                ps.setString(3, reason);
                ps.setString(4, source);
                ps.setString(5, bannedByUuid != null ? bannedByUuid.toString() : null);
                ps.setString(6, bannedByName);
                ps.setLong(7, bannedAt);
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                if (keys.next()) return keys.getInt(1);
            } catch (SQLException e) { e.printStackTrace(); }
            return -1;
        });
    }

    public void markTrackedBanInactive(UUID targetUuid) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE tracked_bans SET active = 0 WHERE target_uuid = ? AND active = 1")) {
                ps.setString(1, targetUuid.toString()); ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public void markTrackedBanInactiveByName(String targetName) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE tracked_bans SET active = 0 WHERE lower(target_name) = lower(?) AND active = 1")) {
                ps.setString(1, targetName); ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public void setBanFirstPrompted(int banId, long whenMs) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE tracked_bans SET first_prompted_at = ? WHERE id = ? AND first_prompted_at IS NULL")) {
                ps.setLong(1, whenMs); ps.setInt(2, banId); ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public void saveBanReviewResponse(int banId, UUID councilUuid, String response) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR REPLACE INTO ban_review_responses (ban_id, council_uuid, response, responded_at) VALUES (?,?,?,?)")) {
                ps.setInt(1, banId); ps.setString(2, councilUuid.toString());
                ps.setString(3, response); ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
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
                WHERE b.active = 1 AND b.banned_at >= ?
                  AND (b.first_prompted_at IS NULL OR b.first_prompted_at >= ?)
                  AND NOT EXISTS (SELECT 1 FROM ban_review_responses r WHERE r.ban_id = b.id AND r.council_uuid = ?)
                ORDER BY b.banned_at DESC LIMIT ?
                """)) {
                ps.setLong(1, after);
                ps.setLong(2, expireBefore);
                ps.setString(3, councilUuid.toString());
                ps.setInt(4, Math.max(1, max * 3));
                ResultSet rs = ps.executeQuery();
                while (rs.next()) list.add(rowToTrackedBan(rs));
            } catch (SQLException e) { e.printStackTrace(); }
            return list;
        });
    }

    public CompletableFuture<TrackedBan> getTrackedBanAsync(int id) {
        return supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM tracked_bans WHERE id = ?")) {
                ps.setInt(1, id);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rowToTrackedBan(rs);
            } catch (SQLException e) { e.printStackTrace(); }
            return null;
        });
    }

    public CompletableFuture<Boolean> hasBanReviewResponseAsync(int banId, UUID councilUuid) {
        return supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM ban_review_responses WHERE ban_id = ? AND council_uuid = ?")) {
                ps.setInt(1, banId); ps.setString(2, councilUuid.toString());
                return ps.executeQuery().next();
            } catch (SQLException e) { e.printStackTrace(); }
            return false;
        });
    }

    public CompletableFuture<Proposal> findActivePardonForTargetAsync(String targetName) {
        return supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM proposals
                WHERE cancelled = 0 AND executed = 0 AND expires_at > ?
                  AND type IN ('PARDON', 'REPARDON') AND lower(target) = lower(?)
                ORDER BY id DESC LIMIT 1
                """)) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, targetName);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    Proposal p = rowToProposal(rs);
                    loadVotesInternal(p);
                    return p;
                }
            } catch (SQLException e) { e.printStackTrace(); }
            return null;
        });
    }

    public record BugGate(boolean ok, boolean blocked, String detail) {
        public static BugGate allow() { return new BugGate(true, false, null); }
        public static BugGate deny(String detail) { return new BugGate(false, false, detail); }
        public static BugGate blocked(String detail) { return new BugGate(false, true, detail); }
    }

    public record CloseResult(String error, String autoBlockedName) {
        public static CloseResult fail(String error) { return new CloseResult(error, null); }
        public static CloseResult ok() { return new CloseResult(null, null); }
        public static CloseResult okBlocked(String name) { return new CloseResult(null, name); }
    }

    public CompletableFuture<BugGate> canFileBugAsync(UUID uuid, int cooldownSec, int maxOpen, int maxDay, int minHours) {
        return supplyAsync(() -> canFileBugInternal(uuid, cooldownSec, maxOpen, maxDay, minHours));
    }

    private BugGate canFileBugInternal(UUID uuid, int cooldownSec, int maxOpen, int maxDay, int minHours) {
        String u = uuid.toString();
        try {
            try (PreparedStatement ps = connection.prepareStatement("SELECT reason FROM bug_blocks WHERE uuid = ?")) {
                ps.setString(1, u);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    String r = rs.getString(1);
                    return BugGate.blocked(r != null && !r.isBlank() ? r : "blocked");
                }
            }
            if (minHours > 0) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT total_playtime FROM player_meta WHERE uuid = ?")) {
                    ps.setString(1, u);
                    ResultSet rs = ps.executeQuery();
                    long ticks = rs.next() ? rs.getLong(1) : 0L;
                    long hours = ticks / 72000L;
                    if (hours < minHours) {
                        return BugGate.deny("Play at least " + minHours + " hour"
                                + (minHours == 1 ? "" : "s") + " before filing bugs (you have ~" + hours + ").");
                    }
                }
            }
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT created_at FROM bugs WHERE reporter_uuid = ? ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, u);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    long last = rs.getLong(1);
                    long wait = cooldownSec * 1000L - (now - last);
                    if (wait > 0) {
                        long mins = Math.max(1, wait / 60000L);
                        return BugGate.deny("Wait " + mins + " more minute(s) before filing another bug.");
                    }
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM bugs WHERE reporter_uuid = ? AND status IN ('open','needs_info','in_progress')")) {
                ps.setString(1, u);
                ResultSet rs = ps.executeQuery();
                int open = rs.next() ? rs.getInt(1) : 0;
                if (open >= maxOpen) {
                    return BugGate.deny("You already have " + open + " open bug(s). Reply on those or wait until they close.");
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM bugs WHERE reporter_uuid = ? AND created_at > ?")) {
                ps.setString(1, u);
                ps.setLong(2, now - 86_400_000L);
                ResultSet rs = ps.executeQuery();
                int day = rs.next() ? rs.getInt(1) : 0;
                if (day >= maxDay) {
                    return BugGate.deny("Daily limit is " + maxDay + " new reports.");
                }
            }
            return BugGate.allow();
        } catch (SQLException e) {
            e.printStackTrace();
            return BugGate.deny("Could not check report limits. Try again.");
        }
    }

    public CompletableFuture<BugReport> findDuplicateOpenBugAsync(String title) {
        return supplyAsync(() -> {
            String norm = normalizeTitle(title);
            if (norm.isEmpty()) return null;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM bugs WHERE status IN ('open','needs_info','in_progress') AND title_norm = ? ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, norm);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rowToBug(rs);
            } catch (SQLException e) { e.printStackTrace(); }
            return null;
        });
    }

    public CompletableFuture<Integer> createBugAsync(UUID uuid, String name, String title, String location) {
        return supplyAsync(() -> {
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO bugs (reporter_uuid, reporter_name, title, title_norm, status, created_at, updated_at, report_count, location) VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setString(3, CoordRedact.apply(title));
                ps.setString(4, normalizeTitle(title));
                ps.setString(5, BugReport.OPEN);
                ps.setLong(6, now);
                ps.setLong(7, now);
                ps.setInt(8, 1);
                ps.setString(9, null);
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                if (!keys.next()) return -1;
                int id = keys.getInt(1);
                insertBugEvent(id, now, "report", uuid, name, title);
                try (PreparedStatement plus = connection.prepareStatement(
                        "INSERT OR IGNORE INTO bug_plusones (bug_id, uuid) VALUES (?, ?)")) {
                    plus.setInt(1, id);
                    plus.setString(2, uuid.toString());
                    plus.executeUpdate();
                }
                log("Bug #" + id + " filed by " + name + ": " + title);
                return id;
            } catch (SQLException e) {
                e.printStackTrace();
                return -1;
            }
        });
    }

    public CompletableFuture<BugReport> getBugAsync(int id) {
        return supplyAsync(() -> getBugInternal(id));
    }

    private BugReport getBugInternal(int id) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM bugs WHERE id = ?")) {
            ps.setInt(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rowToBug(rs);
        } catch (SQLException e) { e.printStackTrace(); }
        return null;
    }

    public CompletableFuture<List<BugReport>> listActiveBugsAsync(int limit) {
        return supplyAsync(() -> listBugs("status IN ('open','needs_info','in_progress') ORDER BY id DESC LIMIT ?", limit));
    }

    public CompletableFuture<List<BugReport>> listClosedBugsAsync(int limit) {
        return supplyAsync(() -> listBugs("status IN ('fixed','closed') ORDER BY updated_at DESC LIMIT ?", limit));
    }

    public CompletableFuture<List<BugReport>> listNeedsInfoForAsync(UUID uuid) {
        return supplyAsync(() -> {
            List<BugReport> list = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM bugs WHERE reporter_uuid = ? AND status = 'needs_info' ORDER BY id DESC LIMIT 5")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                while (rs.next()) list.add(rowToBug(rs));
            } catch (SQLException e) { e.printStackTrace(); }
            return list;
        });
    }

    private List<BugReport> listBugs(String where, int limit) {
        List<BugReport> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM bugs WHERE " + where)) {
            ps.setInt(1, Math.max(1, Math.min(limit, 50)));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) list.add(rowToBug(rs));
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    public CompletableFuture<List<BugReport.Event>> listBugEventsAsync(int id, int limit) {
        return supplyAsync(() -> {
            List<BugReport.Event> list = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT at, kind, actor_name, text FROM bug_events WHERE bug_id = ? ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, id);
                ps.setInt(2, Math.max(1, Math.min(limit, 20)));
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    list.add(new BugReport.Event(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                }
                Collections.reverse(list);
            } catch (SQLException e) { e.printStackTrace(); }
            return list;
        });
    }

    public CompletableFuture<String> replyBugAsync(int id, UUID uuid, String name, String text) {
        return supplyAsync(() -> {
            BugReport b = getBugInternal(id);
            if (b == null) return "No bug #" + id + ".";
            if (!b.isActive()) return "Bug #" + id + " is " + b.statusLabel().toLowerCase(Locale.ROOT) + ".";
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE bugs SET updated_at = ?, last_player_reply_at = ?, question = NULL, status = CASE WHEN status = 'needs_info' THEN 'open' ELSE status END WHERE id = ?")) {
                ps.setLong(1, now);
                ps.setLong(2, now);
                ps.setInt(3, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
                return "Failed to save reply.";
            }
            insertBugEvent(id, now, "reply", uuid, name, CoordRedact.apply(text));
            log("Bug #" + id + " reply from " + name);
            return null;
        });
    }

    public CompletableFuture<String> confirmBugAsync(int id, UUID uuid, String name) {
        return supplyAsync(() -> {
            BugReport b = getBugInternal(id);
            if (b == null) return "No bug #" + id + ".";
            if (!b.isActive()) return "Bug #" + id + " is not open.";
            if (b.reporterUuid.equals(uuid)) return "That is your report. Wait for a fix, or /bug reply " + id + " with more detail.";
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR IGNORE INTO bug_plusones (bug_id, uuid) VALUES (?, ?)")) {
                ps.setInt(1, id);
                ps.setString(2, uuid.toString());
                int added = ps.executeUpdate();
                if (added == 0) return "You already confirmed bug #" + id + ".";
            } catch (SQLException e) {
                e.printStackTrace();
                return "Failed to confirm.";
            }
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE bugs SET report_count = report_count + 1, updated_at = ? WHERE id = ?")) {
                ps.setLong(1, now);
                ps.setInt(2, id);
                ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
            insertBugEvent(id, now, "confirm", uuid, name, null);
            return null;
        });
    }

    public CompletableFuture<CloseResult> closeBugAsync(int id, UUID actor, String actorName, String note, boolean abuse, int autoBlockAt) {
        return supplyAsync(() -> {
            BugReport b = getBugInternal(id);
            if (b == null) return CloseResult.fail("No bug #" + id + ".");
            if (!b.isActive() && !"fixed".equals(b.status)) {
                return CloseResult.fail("Bug #" + id + " is already closed.");
            }
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE bugs SET status = 'closed', updated_at = ?, closed_by_uuid = ?, closed_by_name = ?, close_note = ?, question = NULL WHERE id = ?")) {
                ps.setLong(1, now);
                ps.setString(2, actor != null ? actor.toString() : null);
                ps.setString(3, actorName);
                ps.setString(4, CoordRedact.apply(note));
                ps.setInt(5, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
                return CloseResult.fail("Failed to close.");
            }
            insertBugEvent(id, now, "close", actor, actorName, note);
            log("Bug #" + id + " closed by " + actorName + ": " + note);
            if (!abuse) return CloseResult.ok();
            int falseCloses = 0;
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO bug_reporters (uuid, name, false_closes) VALUES (?, ?, 1) ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, false_closes = false_closes + 1")) {
                ps.setString(1, b.reporterUuid.toString());
                ps.setString(2, b.reporterName);
                ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
            try (PreparedStatement ps = connection.prepareStatement("SELECT false_closes FROM bug_reporters WHERE uuid = ?")) {
                ps.setString(1, b.reporterUuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) falseCloses = rs.getInt(1);
            } catch (SQLException e) { e.printStackTrace(); }
            if (autoBlockAt > 0 && falseCloses >= autoBlockAt) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT OR REPLACE INTO bug_blocks (uuid, name, reason, by_uuid, by_name, at) VALUES (?,?,?,?,?,?)")) {
                    ps.setString(1, b.reporterUuid.toString());
                    ps.setString(2, b.reporterName);
                    ps.setString(3, "auto: " + falseCloses + " false/spam reports");
                    ps.setString(4, actor != null ? actor.toString() : null);
                    ps.setString(5, actorName);
                    ps.setLong(6, now);
                    ps.executeUpdate();
                } catch (SQLException e) { e.printStackTrace(); }
                log("Auto-blocked " + b.reporterName + " from bug reports (" + falseCloses + " false closes)");
                return CloseResult.okBlocked(b.reporterName);
            }
            return CloseResult.ok();
        });
    }

    public CompletableFuture<String> reopenBugAsync(int id, UUID actor, String actorName) {
        return supplyAsync(() -> {
            BugReport b = getBugInternal(id);
            if (b == null) return "No bug #" + id + ".";
            if (b.isActive()) return "Bug #" + id + " is already open.";
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE bugs SET status = 'open', updated_at = ?, closed_by_uuid = NULL, closed_by_name = NULL, close_note = NULL WHERE id = ?")) {
                ps.setLong(1, now);
                ps.setInt(2, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
                return "Failed to reopen.";
            }
            insertBugEvent(id, now, "reopen", actor, actorName, null);
            log("Bug #" + id + " reopened by " + actorName);
            return null;
        });
    }

    public CompletableFuture<String> askBugAsync(int id, UUID actor, String actorName, String question) {
        return supplyAsync(() -> {
            BugReport b = getBugInternal(id);
            if (b == null) return "No bug #" + id + ".";
            if (!b.isActive()) return "Bug #" + id + " is not open.";
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE bugs SET status = 'needs_info', question = ?, updated_at = ? WHERE id = ?")) {
                ps.setString(1, CoordRedact.apply(question));
                ps.setLong(2, now);
                ps.setInt(3, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
                return "Failed to save question.";
            }
            insertBugEvent(id, now, "ask", actor, actorName, question);
            log("Bug #" + id + " needs info: " + question);
            return null;
        });
    }

    public CompletableFuture<String> isBugBlockedAsync(UUID uuid) {
        return supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT reason FROM bug_blocks WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rs.getString(1);
            } catch (SQLException e) { e.printStackTrace(); }
            return null;
        });
    }

    public CompletableFuture<String> setBugBlockAsync(String playerName, boolean blocking, String reason, UUID actor, String actorName) {
        return supplyAsync(() -> {
            UUID target = resolvePlayerUuid(playerName);
            if (target == null) return "Unknown player '" + playerName + "'.";
            String name = nameOf(target, playerName);
            long now = System.currentTimeMillis();
            try {
                if (blocking) {
                    try (PreparedStatement ps = connection.prepareStatement(
                            "INSERT OR REPLACE INTO bug_blocks (uuid, name, reason, by_uuid, by_name, at) VALUES (?,?,?,?,?,?)")) {
                        ps.setString(1, target.toString());
                        ps.setString(2, name);
                        ps.setString(3, reason);
                        ps.setString(4, actor != null ? actor.toString() : null);
                        ps.setString(5, actorName);
                        ps.setLong(6, now);
                        ps.executeUpdate();
                    }
                    log("Bug-block " + name + " by " + actorName + ": " + reason);
                } else {
                    try (PreparedStatement ps = connection.prepareStatement("DELETE FROM bug_blocks WHERE uuid = ?")) {
                        ps.setString(1, target.toString());
                        if (ps.executeUpdate() == 0) return name + " is not blocked.";
                    }
                    try (PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO bug_reporters (uuid, name, false_closes) VALUES (?, ?, 0) ON CONFLICT(uuid) DO UPDATE SET false_closes = 0, name = excluded.name")) {
                        ps.setString(1, target.toString());
                        ps.setString(2, name);
                        ps.executeUpdate();
                    }
                    log("Bug-unblock " + name + " by " + actorName);
                }
                return null;
            } catch (SQLException e) {
                e.printStackTrace();
                return "Database error.";
            }
        });
    }

    private UUID resolvePlayerUuid(String name) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uuid FROM player_meta WHERE lower(name) = lower(?) LIMIT 1")) {
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return UUID.fromString(rs.getString(1));
        } catch (Exception ignored) {}
        try {
            var off = org.bukkit.Bukkit.getOfflinePlayer(name);
            if (off.hasPlayedBefore() || off.isOnline()) return off.getUniqueId();
        } catch (Exception ignored) {}
        return null;
    }

    private String nameOf(UUID uuid, String fallback) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT name FROM player_meta WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String n = rs.getString(1);
                if (n != null && !n.isBlank()) return n;
            }
        } catch (SQLException ignored) {}
        return fallback;
    }

    private void insertBugEvent(int bugId, long at, String kind, UUID actor, String actorName, String text) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO bug_events (bug_id, at, kind, actor_uuid, actor_name, text) VALUES (?,?,?,?,?,?)")) {
            ps.setInt(1, bugId);
            ps.setLong(2, at);
            ps.setString(3, kind);
            ps.setString(4, actor != null ? actor.toString() : null);
            ps.setString(5, actorName);
            ps.setString(6, text == null ? null : CoordRedact.apply(text));
            ps.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); }
    }

    private BugReport rowToBug(ResultSet rs) throws SQLException {
        String closedUuid = rs.getString("closed_by_uuid");
        return new BugReport(
                rs.getInt("id"),
                UUID.fromString(rs.getString("reporter_uuid")),
                rs.getString("reporter_name"),
                rs.getString("title"),
                rs.getString("status"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                closedUuid != null && !closedUuid.isBlank() ? UUID.fromString(closedUuid) : null,
                rs.getString("closed_by_name"),
                rs.getString("close_note"),
                rs.getInt("report_count"),
                rs.getString("location"),
                rs.getString("question")
        );
    }

    public static String normalizeTitle(String title) {
        if (title == null) return "";
        String n = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        return n.replaceAll("\\s+", " ");
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
                byUuid != null && !byUuid.isBlank() ? UUID.fromString(byUuid) : null,
                rs.getString("banned_by_name"),
                rs.getLong("banned_at"),
                firstNull ? null : first,
                rs.getInt("active") == 1
        );
    }
}
