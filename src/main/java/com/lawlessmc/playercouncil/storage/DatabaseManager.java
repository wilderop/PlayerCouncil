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
                if (!plugin.getDataFolder().exists()) {
                    plugin.getDataFolder().mkdirs();
                }
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
            st.execute("CREATE TABLE IF NOT EXISTS votes (proposal_id INTEGER NOT NULL, voter TEXT NOT NULL, yes INTEGER NOT NULL, PRIMARY KEY (proposal_id, voter), FOREIGN KEY (proposal_id) REFERENCES proposals(id))");
            st.execute("CREATE TABLE IF NOT EXISTS council (uuid TEXT PRIMARY KEY, name TEXT, rank INTEGER)");
            st.execute("CREATE TABLE IF NOT EXISTS audit_log (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, message TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS pending_plugin_actions (plugin_name TEXT PRIMARY KEY, enable INTEGER NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS ban_ladder (uuid TEXT PRIMARY KEY, name TEXT, stage INTEGER NOT NULL DEFAULT 0)");
        }
    }

    public void runSync(Runnable work) {
        try {
            dbExecutor.submit(work).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().severe("DB sync task failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void runAsync(Runnable work) {
        dbExecutor.execute(() -> {
            try { work.run(); } catch (Exception e) {
                plugin.getLogger().warning("DB async task failed: " + e.getMessage());
                e.printStackTrace();
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
            try {
                if (connection != null && !connection.isClosed()) connection.close();
            } catch (SQLException e) { e.printStackTrace(); }
        });
        dbExecutor.shutdown();
        try { dbExecutor.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public void pruneOldSnapshots(int days) {
        runAsync(() -> pruneOldSnapshotsInternal(days));
    }

    private void pruneOldSnapshotsInternal(int days) {
        long cutoff = System.currentTimeMillis() - (days * 24L * 60 * 60 * 1000);
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM snapshots WHERE timestamp < ?")) {
            ps.setLong(1, cutoff);
            int removed = ps.executeUpdate();
            if (removed > 0) plugin.getLogger().info("Pruned " + removed + " activity snapshots older than " + days + " days.");
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public void saveSnapshot(ActivitySnapshot snap) {
        runAsync(() -> {
            String sql = "INSERT OR REPLACE INTO snapshots (uuid, timestamp, playtime, walk, fly, mob_kills) VALUES (?,?,?,?,?,?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, snap.getUuid().toString());
                ps.setLong(2, snap.getTimestamp());
                ps.setLong(3, snap.getPlaytime());
                ps.setLong(4, snap.getWalk());
                ps.setLong(5, snap.getFly());
                ps.setLong(6, snap.getMobKills());
                ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public CompletableFuture<List<ActivitySnapshot>> getSnapshotsSinceAsync(UUID uuid, long sinceTimestamp) {
        return supplyAsync(() -> {
            List<ActivitySnapshot> list = new ArrayList<>();
            String sql = "SELECT * FROM snapshots WHERE uuid = ? AND timestamp >= ? ORDER BY timestamp ASC";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, sinceTimestamp);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    list.add(new ActivitySnapshot(uuid, rs.getLong("timestamp"), rs.getLong("playtime"), rs.getLong("walk"), rs.getLong("fly"), rs.getLong("mob_kills")));
                }
            } catch (SQLException e) { e.printStackTrace(); }
            return list;
        });
    }

    public CompletableFuture<ActivitySnapshot> getLatestSnapshotAsync(UUID uuid) {
        return supplyAsync(() -> {
            String sql = "SELECT * FROM snapshots WHERE uuid = ? ORDER BY timestamp DESC LIMIT 1";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    return new ActivitySnapshot(uuid, rs.getLong("timestamp"), rs.getLong("playtime"), rs.getLong("walk"), rs.getLong("fly"), rs.getLong("mob_kills"));
                }
            } catch (SQLException e) { e.printStackTrace(); }
            return null;
        });
    }

    public void upsertPlayerMeta(UUID uuid, String name, long firstJoin, long totalPlaytime) {
        runAsync(() -> {
            String sql = "INSERT INTO player_meta (uuid, name, first_join, total_playtime) VALUES (?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, total_playtime = excluded.total_playtime";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setLong(3, firstJoin);
                ps.setLong(4, totalPlaytime);
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
        String sql = "SELECT " + column + " FROM player_meta WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
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
            String sql = "INSERT INTO council (uuid, name, rank) VALUES (?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                int rank = 1;
                for (Map.Entry<UUID, String> e : members) {
                    ps.setString(1, e.getKey().toString());
                    ps.setString(2, e.getValue());
                    ps.setInt(3, rank++);
                    ps.addBatch();
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
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public CompletableFuture<Integer> createProposalAsync(Proposal.Type type, UUID proposer, String target, String value, long expiresAt) {
        return supplyAsync(() -> {
            String sql = "INSERT INTO proposals (type, proposer, target, value, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, type.name());
                ps.setString(2, proposer.toString());
                ps.setString(3, target);
                ps.setString(4, value);
                ps.setLong(5, System.currentTimeMillis());
                ps.setLong(6, expiresAt);
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                if (keys.next()) return keys.getInt(1);
            } catch (SQLException e) { e.printStackTrace(); }
            return -1;
        });
    }

    public void saveVote(int proposalId, UUID voter, boolean yes) {
        runAsync(() -> {
            String sql = "INSERT OR REPLACE INTO votes (proposal_id, voter, yes) VALUES (?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, proposalId);
                ps.setString(2, voter.toString());
                ps.setInt(3, yes ? 1 : 0);
                ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public void markProposalCancelled(int id) { updateProposalFlag(id, "cancelled", 1); }
    public void markProposalExecuted(int id) { updateProposalFlag(id, "executed", 1); }

    private void updateProposalFlag(int id, String column, int value) {
        runAsync(() -> {
            String sql = "UPDATE proposals SET " + column + " = ? WHERE id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, value);
                ps.setInt(2, id);
                ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public CompletableFuture<List<Proposal>> getActiveProposalsAsync() {
        return supplyAsync(() -> {
            List<Proposal> list = new ArrayList<>();
            String sql = "SELECT * FROM proposals WHERE cancelled = 0 AND executed = 0 AND expires_at > ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, System.currentTimeMillis());
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Proposal p = rowToProposal(rs);
                    loadVotesInternal(p);
                    list.add(p);
                }
            } catch (SQLException e) { e.printStackTrace(); }
            return list;
        });
    }

    public CompletableFuture<Proposal> getProposalAsync(int id) {
        return supplyAsync(() -> {
            String sql = "SELECT * FROM proposals WHERE id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, id);
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

    private Proposal rowToProposal(ResultSet rs) throws SQLException {
        Proposal p = new Proposal(rs.getInt("id"), Proposal.Type.valueOf(rs.getString("type")), UUID.fromString(rs.getString("proposer")), rs.getString("target"), rs.getString("value"), rs.getLong("created_at"), rs.getLong("expires_at"));
        p.setCancelled(rs.getInt("cancelled") == 1);
        p.setExecuted(rs.getInt("executed") == 1);
        return p;
    }

    private void loadVotesInternal(Proposal p) {
        String sql = "SELECT voter, yes FROM votes WHERE proposal_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, p.getId());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) p.addVote(UUID.fromString(rs.getString("voter")), rs.getInt("yes") == 1);
        } catch (SQLException e) { e.printStackTrace(); }
    }

    public void log(String message) {
        runAsync(() -> {
            String sql = "INSERT INTO audit_log (timestamp, message) VALUES (?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, message);
                ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public CompletableFuture<List<String>> getRecentAuditAsync(int limit) {
        return supplyAsync(() -> {
            List<String> list = new ArrayList<>();
            String sql = "SELECT message FROM audit_log ORDER BY id DESC LIMIT ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, limit);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) list.add(rs.getString("message"));
            } catch (SQLException e) { e.printStackTrace(); }
            return list;
        });
    }

    public void setPendingPluginAction(String pluginName, boolean enable) {
        runAsync(() -> {
            String sql = "INSERT OR REPLACE INTO pending_plugin_actions (plugin_name, enable) VALUES (?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, pluginName);
                ps.setInt(2, enable ? 1 : 0);
                ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }

    public Map<String, Boolean> getPendingPluginActionsSync() {
        Map<String, Boolean> map = new ConcurrentHashMap<>();
        runSync(() -> {
            try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT plugin_name, enable FROM pending_plugin_actions")) {
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
            String sql = "SELECT stage FROM ban_ladder WHERE uuid = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();
                if (rs.next()) return rs.getInt("stage");
            } catch (SQLException e) { e.printStackTrace(); }
            return 0;
        });
    }

    public void setBanLadderStage(UUID uuid, String name, int stage) {
        runAsync(() -> {
            String sql = "INSERT INTO ban_ladder (uuid, name, stage) VALUES (?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, stage = excluded.stage";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setInt(3, stage);
                ps.executeUpdate();
            } catch (SQLException e) { e.printStackTrace(); }
        });
    }
}
