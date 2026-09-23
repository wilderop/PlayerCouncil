package com.lawlessmc.playercouncil.bridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Chat dump of TopPlayers sqlite so Fabric /top is not a chest GUI. */
final class TopPlayersDump {
    private static final Path DB = Path.of("/mnt/pool/survival/plugins/TopPlayers/data.db");
    private static final Map<String, Board> BOARDS = new LinkedHashMap<>();

    static {
        BOARDS.put("playtime", new Board("GENERIC:PLAY_ONE_MINUTE", "Play time", true));
        BOARDS.put("kills", new Board("ENTITY:KILL_ENTITY:PLAYER", "Player kills", false));
        BOARDS.put("deaths", new Board("GENERIC:DEATHS", "Deaths", false));
        BOARDS.put("mobs", new Board("GENERIC:MOB_KILLS", "Mob kills", false));
    }

    private record Board(String key, String title, boolean playtime) {}

    private TopPlayersDump() {}

    static List<String> lines(String[] args) {
        if (!Files.isRegularFile(DB)) {
            return List.of("<red>TopPlayers data is not on this host.");
        }
        String which = (args == null || args.length == 0) ? "" : args[0].toLowerCase(Locale.ROOT);
        if (which.isEmpty() || which.equals("help")) {
            List<String> out = new ArrayList<>();
            out.add("<gold>TopPlayers</gold> <gray>(survival stats; chest GUI is on survival)</gray>");
            out.add("<yellow>/top playtime</yellow>  <yellow>/top kills</yellow>  <yellow>/top deaths</yellow>  <yellow>/top mobs</yellow>");
            out.addAll(boardLines(BOARDS.get("playtime")));
            return out;
        }
        Board board = BOARDS.get(which);
        if (board == null) {
            if (which.equals("playerkills") || which.equals("pkills")) board = BOARDS.get("kills");
            else if (which.equals("time") || which.equals("hours")) board = BOARDS.get("playtime");
            else if (which.equals("mobkills")) board = BOARDS.get("mobs");
        }
        if (board == null) {
            return List.of("<red>Unknown board.</red> <gray>Use playtime, kills, deaths, mobs.");
        }
        return boardLines(board);
    }

    private static List<String> boardLines(Board board) {
        List<String> out = new ArrayList<>();
        out.add("<gold>===== " + board.title() + " =====");
        String sql = "SELECT p.name, s.value FROM statistics s JOIN players p ON p.id = s.player_id "
                + "WHERE s.stat_key = ? ORDER BY s.value DESC LIMIT 10";
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + DB.toAbsolutePath());
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, board.key());
            try (ResultSet rs = ps.executeQuery()) {
                int i = 0;
                while (rs.next()) {
                    i++;
                    String name = rs.getString(1);
                    long value = rs.getLong(2);
                    out.add("<yellow>" + i + ".</yellow> <white>" + name
                            + "</white> <gray>-</gray> <aqua>" + format(board, value) + "</aqua>");
                }
                if (i == 0) out.add("<gray>No data.");
            }
        } catch (Exception e) {
            out.add("<red>Could not read TopPlayers: " + e.getMessage());
        }
        return out;
    }

    private static String format(Board board, long value) {
        if (!board.playtime()) return Long.toString(value);
        double hours = value / 72000.0;
        if (hours >= 10) return String.format(Locale.ROOT, "%.0fh", hours);
        return String.format(Locale.ROOT, "%.1fh", hours);
    }
}
