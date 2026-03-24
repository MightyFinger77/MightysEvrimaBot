package com.isle.evrima.bot.db;

import com.isle.evrima.bot.ecosystem.PlayerlistPopulationParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

public final class Database implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Database.class);

    private final Path filePath;
    private final String jdbcUrl;

    public Database(Path filePath) {
        this.filePath = filePath.toAbsolutePath().normalize();
        this.jdbcUrl = "jdbc:sqlite:" + this.filePath;
    }

    public void migrate() throws SQLException {
        Path parent = filePath.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (Exception e) {
                throw new SQLException("Could not create database directory: " + parent, e);
            }
        }
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS steam_links (
                      discord_user_id TEXT NOT NULL PRIMARY KEY,
                      steam_id64 TEXT NOT NULL,
                      linked_at_epoch_sec INTEGER NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS link_codes (
                      code TEXT NOT NULL PRIMARY KEY,
                      discord_user_id TEXT NOT NULL,
                      expires_at_epoch_sec INTEGER NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS audit_log (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      at_epoch_sec INTEGER NOT NULL,
                      actor_discord_id TEXT,
                      action TEXT NOT NULL,
                      detail TEXT
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS economy_balance (
                      discord_user_id TEXT NOT NULL PRIMARY KEY,
                      balance INTEGER NOT NULL DEFAULT 0
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS daily_spin (
                      discord_user_id TEXT NOT NULL PRIMARY KEY,
                      last_yyyymmdd INTEGER NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS parked_dinos (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      discord_user_id TEXT NOT NULL,
                      steam_id64 TEXT NOT NULL,
                      label TEXT,
                      payload_json TEXT NOT NULL,
                      parked_at_epoch_sec INTEGER NOT NULL
                    )""");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_parked_owner ON parked_dinos(discord_user_id)");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS bot_kv (
                      k TEXT NOT NULL PRIMARY KEY,
                      v TEXT NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS seen_server_player_steam (
                      steam_id64 TEXT NOT NULL PRIMARY KEY,
                      first_seen_epoch_sec INTEGER NOT NULL
                    )""");
        }
        LOG.info("Database ready at {}", filePath);
    }

    public Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    @Override
    public void close() {
        // SQLite file-based; nothing to close globally
    }

    public Optional<String> findSteamIdForDiscord(String discordUserId) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT steam_id64 FROM steam_links WHERE discord_user_id = ?")) {
            ps.setString(1, discordUserId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString(1));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<String> findDiscordForSteam(String steamId64) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT discord_user_id FROM steam_links WHERE steam_id64 = ?")) {
            ps.setString(1, steamId64);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString(1));
                }
            }
        }
        return Optional.empty();
    }

    public void putLinkCode(String code, String discordUserId, long expiresAtEpochSec) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO link_codes(code, discord_user_id, expires_at_epoch_sec) VALUES(?,?,?)")) {
            ps.setString(1, code);
            ps.setString(2, discordUserId);
            ps.setLong(3, expiresAtEpochSec);
            ps.executeUpdate();
        }
    }

    public Optional<String> consumeLinkCode(String code, String discordUserId) throws SQLException {
        long now = Instant.now().getEpochSecond();
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                String owner;
                long expires;
                try (PreparedStatement sel = c.prepareStatement(
                        "SELECT discord_user_id, expires_at_epoch_sec FROM link_codes WHERE code = ?")) {
                    sel.setString(1, code);
                    try (ResultSet rs = sel.executeQuery()) {
                        if (!rs.next()) {
                            c.rollback();
                            return Optional.empty();
                        }
                        owner = rs.getString(1);
                        expires = rs.getLong(2);
                    }
                }
                if (now > expires) {
                    try (PreparedStatement del = c.prepareStatement("DELETE FROM link_codes WHERE code = ?")) {
                        del.setString(1, code);
                        del.executeUpdate();
                    }
                    c.commit();
                    return Optional.empty();
                }
                if (!owner.equals(discordUserId)) {
                    c.rollback();
                    return Optional.empty();
                }
                try (PreparedStatement del = c.prepareStatement("DELETE FROM link_codes WHERE code = ?")) {
                    del.setString(1, code);
                    del.executeUpdate();
                }
                c.commit();
                return Optional.of(owner);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public void upsertSteamLink(String discordUserId, String steamId64) throws SQLException {
        long now = Instant.now().getEpochSecond();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO steam_links(discord_user_id, steam_id64, linked_at_epoch_sec) VALUES(?,?,?)
                     ON CONFLICT(discord_user_id) DO UPDATE SET steam_id64 = excluded.steam_id64,
                       linked_at_epoch_sec = excluded.linked_at_epoch_sec
                     """)) {
            ps.setString(1, discordUserId);
            ps.setString(2, steamId64);
            ps.setLong(3, now);
            ps.executeUpdate();
        }
    }

    public void deleteSteamLink(String discordUserId) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM steam_links WHERE discord_user_id = ?")) {
            ps.setString(1, discordUserId);
            ps.executeUpdate();
        }
    }

    public void appendAudit(String actorDiscordId, String action, String detail) throws SQLException {
        long now = Instant.now().getEpochSecond();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO audit_log(at_epoch_sec, actor_discord_id, action, detail) VALUES(?,?,?,?)")) {
            ps.setLong(1, now);
            ps.setString(2, actorDiscordId);
            ps.setString(3, action);
            ps.setString(4, detail);
            ps.executeUpdate();
        }
    }

    public int getBalance(String discordUserId) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT balance FROM economy_balance WHERE discord_user_id = ?")) {
            ps.setString(1, discordUserId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    public void addBalance(String discordUserId, int delta) throws SQLException {
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                int cur = 0;
                try (PreparedStatement sel = c.prepareStatement(
                        "SELECT balance FROM economy_balance WHERE discord_user_id = ?")) {
                    sel.setString(1, discordUserId);
                    try (ResultSet rs = sel.executeQuery()) {
                        if (rs.next()) {
                            cur = rs.getInt(1);
                        }
                    }
                }
                int next = Math.max(0, cur + delta);
                try (PreparedStatement up = c.prepareStatement("""
                        INSERT INTO economy_balance(discord_user_id, balance) VALUES(?,?)
                        ON CONFLICT(discord_user_id) DO UPDATE SET balance = excluded.balance
                        """)) {
                    up.setString(1, discordUserId);
                    up.setInt(2, next);
                    up.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public OptionalLong lastSpinDay(String discordUserId) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT last_yyyymmdd FROM daily_spin WHERE discord_user_id = ?")) {
            ps.setString(1, discordUserId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return OptionalLong.of(rs.getLong(1));
                }
            }
        }
        return OptionalLong.empty();
    }

    public void setLastSpinDay(String discordUserId, int yyyymmdd) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO daily_spin(discord_user_id, last_yyyymmdd) VALUES(?,?)
                     ON CONFLICT(discord_user_id) DO UPDATE SET last_yyyymmdd = excluded.last_yyyymmdd
                     """)) {
            ps.setString(1, discordUserId);
            ps.setInt(2, yyyymmdd);
            ps.executeUpdate();
        }
    }

    public long insertParkedDino(String discordUserId, String steamId64, String label, String payloadJson) throws SQLException {
        long now = Instant.now().getEpochSecond();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO parked_dinos(discord_user_id, steam_id64, label, payload_json, parked_at_epoch_sec)
                     VALUES(?,?,?,?,?)
                     """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, discordUserId);
            ps.setString(2, steamId64);
            ps.setString(3, label);
            ps.setString(4, payloadJson);
            ps.setLong(5, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        return -1L;
    }

    public List<ParkedRow> listParked(String discordUserId) throws SQLException {
        List<ParkedRow> rows = new ArrayList<>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, label, parked_at_epoch_sec FROM parked_dinos WHERE discord_user_id = ? ORDER BY id DESC")) {
            ps.setString(1, discordUserId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new ParkedRow(rs.getLong(1), rs.getString(2), rs.getLong(3)));
                }
            }
        }
        return rows;
    }

    public boolean deleteParked(long id, String discordUserId) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM parked_dinos WHERE id = ? AND discord_user_id = ?")) {
            ps.setLong(1, id);
            ps.setString(2, discordUserId);
            return ps.executeUpdate() == 1;
        }
    }

    public record ParkedRow(long id, String label, long parkedAtEpochSec) {}

    public Optional<String> getBotKv(String key) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT v FROM bot_kv WHERE k = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString(1));
                }
            }
        }
        return Optional.empty();
    }

    public OptionalLong getBotKvLong(String key) throws SQLException {
        Optional<String> s = getBotKv(key);
        if (s.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(s.get().trim()));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    public void putBotKv(String key, String value) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO bot_kv(k, v) VALUES(?,?)
                     ON CONFLICT(k) DO UPDATE SET v = excluded.v
                     """)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    public void deleteBotKv(String key) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM bot_kv WHERE k = ?")) {
            ps.setString(1, key);
            ps.executeUpdate();
        }
    }

    /** Returns all key/value pairs where key starts with the given prefix. */
    public Map<String, String> listBotKvByPrefix(String prefix) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT k, v FROM bot_kv WHERE k LIKE ? ORDER BY k")) {
            ps.setString(1, prefix + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return out;
    }

    /**
     * Records Steam IDs seen in RCON {@code playerlist} text so we can show a running "unique players seen" count.
     * Existing rows are left unchanged ({@code INSERT OR IGNORE}).
     */
    public void recordSteamIdsFromPlayerlistRaw(String rawPlayerlist) throws SQLException {
        if (rawPlayerlist == null || rawPlayerlist.isBlank()) {
            return;
        }
        Set<String> ids = PlayerlistPopulationParser.distinctSteamIds(rawPlayerlist);
        if (ids.isEmpty()) {
            return;
        }
        long now = Instant.now().getEpochSecond();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT OR IGNORE INTO seen_server_player_steam(steam_id64, first_seen_epoch_sec) VALUES(?,?)
                     """)) {
            for (String id : ids) {
                ps.setString(1, id);
                ps.setLong(2, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public long countSeenServerSteamIds() throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM seen_server_player_steam")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
                return 0L;
            }
        }
    }
}
