package com.lawlessmc.playercouncil.storage;

import com.lawlessmc.playercouncil.PlayerCouncilPlugin;
import com.lawlessmc.playercouncil.models.ActivitySnapshot;
import com.lawlessmc.playercouncil.models.Proposal;

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
            // Ban review tables
            st.execute("CREATE TABLE IF NOT EXISTS scoreboard_optin (uuid TEXT PRIMARY KEY, enabled INTEGER NOT NULL DEFAULT 1)");
            st.execute("CREATE TABLE IF NOT EXISTS ban_propose_cooldown (uuid TEXT PRIMARY KEY, until_ms BIGINT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS player_ips (uuid TEXT NOT NULL, ip TEXT NOT NULL, first_seen BIGINT, last_seen BIGINT, PRIMARY KEY (uuid, ip))");
            st.execute("CREATE TABLE IF NOT EXISTS tracked_bans (id INTEGER PRIMARY KEY AUTOINCREMENT, target_uuid TEXT NOT NULL, target_name TEXT NOT NULL, reason TEXT, source TEXT, banned_by_uuid TEXT, banned_by_name TEXT, banned_at BIGINT NOT NULL, first_prompted_at BIGINT, active INTEGER NOT NULL DEFAULT 1)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_tracked_bans_active ON tracked_bans(active, banned_at)");
            st.execute("CREATE TABLE IF NOT EXISTS ban_review_responses (ban_id INTEGER NOT NULL, council_uuid TEXT NOT NULL, response TEXT NOT NULL, responded_at BIGINT NOT NULL, PRIMARY KEY (ban_id, council_uuid))");
            try { st.execute("ALTER TABLE proposals ADD COLUMN reason TEXT"); } catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE proposals ADD COLUMN discord_thread_id TEXT"); } catch (SQLException ignored) {}
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
        runAsync(() -> {
            try {
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
        });
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
        runAsync(() -> {
            String sql = "INSERT INTO player_meta (uuid, name, first_join, total_playtime) VALUES (?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, total_playtime = excluded.total_playtime";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString()); ps.setString(2, name);
                ps.setLong(3, firstJoin); ps.setLong(4, totalPlaytime);
                ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
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

    // ---- Scoreboard opt-in ----
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

    // ---- Ban propose cooldown ----
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

    // ---- Player IPs ----
    public void recordPlayerIp(UUID uuid, String ip) {
        long now = System.currentTimeMillis();
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO player_ips (uuid, ip, first_seen, last_seen) VALUES (?,?,?,?) ON CONFLICT(uuid, ip) DO UPDATE SET last_seen = excluded.last_seen")) {
                ps.setString(1, uuid.toString()); ps.setString(2, ip);
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

    // ---- Tracked bans (ban review) ----
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
