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
                    st.execute("PRAGMA journal_mode=DELETE");
                    st.execute("PRAGMA busy_timeout=5000");
                    st.execute("PRAGMA synchronous=NORMAL");
                    st.execute("PRAGMA foreign_keys=ON");
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
            st.execute("CREATE TABLE IF NOT EXISTS proposals (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, proposer TEXT NOT NULL, target TEXT NOT NULL, value TEXT, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, cancelled INTEGER DEFAULT 0, executed INTEGER DEFAULT 0, discord_thread_id TEXT, reason TEXT)");
            try { st.execute("ALTER TABLE proposals ADD COLUMN discord_thread_id TEXT"); } catch (SQLException ignored) {}
            try { st.execute("ALTER TABLE proposals ADD COLUMN reason TEXT"); } catch (SQLException ignored) {}
            st.execute("CREATE TABLE IF NOT EXISTS votes (proposal_id INTEGER NOT NULL, voter TEXT NOT NULL, yes INTEGER NOT NULL, PRIMARY KEY (proposal_id, voter))");
            st.execute("CREATE TABLE IF NOT EXISTS council (uuid TEXT PRIMARY KEY, name TEXT, rank INTEGER)");
            st.execute("CREATE TABLE IF NOT EXISTS audit_log (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, message TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS pending_plugin_actions (plugin_name TEXT PRIMARY KEY, enable INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS ban_ladder (uuid TEXT PRIMARY KEY, name TEXT, stage INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS snapshot_stats (uuid TEXT NOT NULL, timestamp INTEGER NOT NULL, stat TEXT NOT NULL, value INTEGER NOT NULL, PRIMARY KEY (uuid, timestamp, stat))");
            st.execute("CREATE TABLE IF NOT EXISTS ban_propose_cooldown (uuid TEXT PRIMARY KEY, until_ms INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS player_ips (uuid TEXT NOT NULL, ip TEXT NOT NULL, last_seen INTEGER NOT NULL, PRIMARY KEY (uuid, ip))");
            st.execute("CREATE INDEX IF NOT EXISTS idx_player_ips_ip ON player_ips(ip)");
            st.execute("CREATE TABLE IF NOT EXISTS player_prefs (uuid TEXT PRIMARY KEY, scoreboard INTEGER NOT NULL DEFAULT 0)");
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

    public CompletableFuture<Integer> createProposalAsync(Proposal.Type type, UUID proposer, String target,
                                                          String value, String reason, long expiresAt) {
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
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE proposals SET discord_thread_id = ? WHERE id = ?")) {
                ps.setString(1, threadId);
                ps.setInt(2, proposalId);
                ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public void setScoreboardOptIn(UUID uuid, boolean on) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO player_prefs (uuid, scoreboard) VALUES (?, ?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET scoreboard = excluded.scoreboard")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, on ? 1 : 0);
                ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public CompletableFuture<Boolean> isScoreboardOptInAsync(UUID uuid) {
        return supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT scoreboard FROM player_prefs WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rs.getInt(1) == 1;
            } catch (SQLException e) { e.printStackTrace(); }
            return false;
        });
    }

    public CompletableFuture<Set<UUID>> loadScoreboardOptInsAsync() {
        return supplyAsync(() -> {
            Set<UUID> set = new HashSet<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT uuid FROM player_prefs WHERE scoreboard = 1");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) set.add(UUID.fromString(rs.getString(1)));
            } catch (SQLException e) { e.printStackTrace(); }
            return set;
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

    public void setBanProposeCooldown(UUID uuid, long untilMs) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO ban_propose_cooldown (uuid, until_ms) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET until_ms = excluded.until_ms")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, untilMs);
                ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public CompletableFuture<Long> getBanProposeCooldownAsync(UUID uuid) {
        return supplyAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT until_ms FROM ban_propose_cooldown WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rs.getLong(1);
            } catch (SQLException e) { e.printStackTrace(); }
            return 0L;
        });
    }

    public void clearBanProposeCooldown(UUID uuid) {
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM ban_propose_cooldown WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public void recordPlayerIp(UUID uuid, String ip) {
        if (ip == null || ip.isBlank()) return;
        String clean = ip.startsWith("/") ? ip.substring(1) : ip;
        final String ipFinal = clean;
        runAsync(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO player_ips (uuid, ip, last_seen) VALUES (?, ?, ?) " +
                    "ON CONFLICT(uuid, ip) DO UPDATE SET last_seen = excluded.last_seen")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, ipFinal);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public CompletableFuture<Map<UUID, Set<UUID>>> getIpRelatedGroupsAsync(Collection<UUID> candidates) {
        return supplyAsync(() -> {
            Set<UUID> cand = new HashSet<>(candidates);
            Map<UUID, Set<String>> ipsByUuid = new HashMap<>();
            Map<String, Set<UUID>> uuidsByIp = new HashMap<>();
            if (cand.isEmpty()) return Map.of();

            List<UUID> list = new ArrayList<>(cand);
            StringBuilder placeholders = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) placeholders.append(',');
                placeholders.append('?');
            }
            String sql = "SELECT uuid, ip FROM player_ips WHERE uuid IN (" + placeholders + ")";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (int i = 0; i < list.size(); i++) {
                    ps.setString(i + 1, list.get(i).toString());
                }
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    UUID u = UUID.fromString(rs.getString("uuid"));
                    String ip = rs.getString("ip");
                    ipsByUuid.computeIfAbsent(u, k -> new HashSet<>()).add(ip);
                    uuidsByIp.computeIfAbsent(ip, k -> new HashSet<>()).add(u);
                }
            } catch (SQLException e) { e.printStackTrace(); }

            Map<UUID, Set<UUID>> related = new HashMap<>();
            for (UUID u : cand) {
                Set<UUID> group = new HashSet<>();
                group.add(u);
                for (String ip : ipsByUuid.getOrDefault(u, Set.of())) {
                    for (UUID other : uuidsByIp.getOrDefault(ip, Set.of())) {
                        if (cand.contains(other)) group.add(other);
                    }
                }
                related.put(u, group);
            }
            return related;
        });
    }
}
