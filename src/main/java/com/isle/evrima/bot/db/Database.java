package com.isle.evrima.bot.db;

import com.isle.evrima.bot.config.EconomyParkingSlotsConfig;
import com.isle.evrima.bot.dino.ParkedDinoPayload;
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
                    CREATE TABLE IF NOT EXISTS economy_extra_parking_slots (
                      discord_user_id TEXT NOT NULL PRIMARY KEY,
                      extra_slots INTEGER NOT NULL DEFAULT 0
                    )""");
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
            int removed = st.executeUpdate("""
                    DELETE FROM bot_kv WHERE k = 'species_population_control_runtime_enabled'
                    OR k LIKE 'species_population_control_cap_override:%'
                    OR k = 'scheduled_wipecorpses_runtime_enabled'
                    OR k = 'scheduled_wipecorpses_runtime_interval_minutes'
                    OR k = 'scheduled_wipecorpses_runtime_warn_before_minutes'
                    OR k = 'scheduled_wipecorpses_runtime_announce_message'
                    OR k = 'scheduled_wipecorpses_runtime_dynamic_max_players'
                    OR k = 'scheduled_wipecorpses_runtime_dynamic_enable_percent'
                    OR k = 'scheduled_wipecorpses_runtime_dynamic_disable_grace_seconds'""");
            if (removed > 0) {
                LOG.info("Removed {} obsolete bot_kv row(s) (scheduler settings now live in config.yml only)", removed);
            }
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

    /** Most recent owned slot id whose label matches case-insensitively after trim. */
    public OptionalLong findOwnedParkedIdByLabel(String discordUserId, String label) throws SQLException {
        if (discordUserId == null || discordUserId.isBlank() || label == null) {
            return OptionalLong.empty();
        }
        String wanted = label.trim();
        if (wanted.isEmpty()) {
            return OptionalLong.empty();
        }
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT id
                     FROM parked_dinos
                     WHERE discord_user_id = ?
                       AND lower(trim(coalesce(label, ''))) = lower(trim(?))
                     ORDER BY id DESC
                     LIMIT 1
                     """)) {
            ps.setString(1, discordUserId);
            ps.setString(2, wanted);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return OptionalLong.of(rs.getLong(1));
                }
            }
        }
        return OptionalLong.empty();
    }

    /**
     * Overwrites an owned slot in place (same id) with fresh snapshot values.
     *
     * @return true if exactly one row updated
     */
    public boolean overwriteParkedDino(
            long id,
            String discordUserId,
            String steamId64,
            String label,
            String payloadJson,
            long parkedAtEpochSec
    ) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE parked_dinos
                     SET steam_id64 = ?, label = ?, payload_json = ?, parked_at_epoch_sec = ?
                     WHERE id = ? AND discord_user_id = ?
                     """)) {
            ps.setString(1, steamId64);
            ps.setString(2, label);
            ps.setString(3, payloadJson);
            ps.setLong(4, parkedAtEpochSec);
            ps.setLong(5, id);
            ps.setString(6, discordUserId);
            boolean ok = ps.executeUpdate() == 1;
            if (ok) {
                // Overwrite should behave like "fresh park" for per-slot counters.
                clearParkSlotAuxiliaryKv(discordUserId, id);
            }
            return ok;
        }
    }

    public List<ParkedRow> listParked(String discordUserId) throws SQLException {
        List<ParkedRow> rows = new ArrayList<>();
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, label, parked_at_epoch_sec, payload_json FROM parked_dinos WHERE discord_user_id = ? ORDER BY id DESC")) {
            ps.setString(1, discordUserId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String payload = rs.getString(4);
                    String sum = ParkedDinoPayload.readSummaryFromStoredJson(payload).oneLine();
                    rows.add(new ParkedRow(rs.getLong(1), rs.getString(2), rs.getLong(3), sum));
                }
            }
        }
        return rows;
    }

    /**
     * Deletes every parking slot tied to this SteamID64 (any Discord owner). Rare bulk op; clears per-slot KV keys.
     *
     * @return number of rows removed
     */
    public int deleteAllParkedForSteamId(String steamId64) throws SQLException {
        if (steamId64 == null || steamId64.isBlank()) {
            return 0;
        }
        String trim = steamId64.trim();
        List<ParkOwnerRow> rows = new ArrayList<>();
        try (Connection c = open();
             PreparedStatement sel = c.prepareStatement(
                     "SELECT id, discord_user_id FROM parked_dinos WHERE steam_id64 = ?")) {
            sel.setString(1, trim);
            try (ResultSet rs = sel.executeQuery()) {
                while (rs.next()) {
                    rows.add(new ParkOwnerRow(rs.getLong(1), rs.getString(2)));
                }
            }
        }
        if (rows.isEmpty()) {
            return 0;
        }
        try (Connection c = open();
             PreparedStatement del = c.prepareStatement("DELETE FROM parked_dinos WHERE steam_id64 = ?")) {
            del.setString(1, trim);
            int n = del.executeUpdate();
            for (ParkOwnerRow r : rows) {
                clearParkSlotAuxiliaryKv(r.discordUserId(), r.id());
            }
            return n;
        }
    }

    public record ParkOwnerRow(long id, String discordUserId) {}

    public Optional<ParkedFullRow> findParked(long id, String discordUserId) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT steam_id64, label, payload_json, parked_at_epoch_sec FROM parked_dinos WHERE id = ? AND discord_user_id = ?")) {
            ps.setLong(1, id);
            ps.setString(2, discordUserId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new ParkedFullRow(
                            id,
                            rs.getString(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getLong(4)));
                }
            }
        }
        return Optional.empty();
    }

    public boolean deleteParked(long id, String discordUserId) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM parked_dinos WHERE id = ? AND discord_user_id = ?")) {
            ps.setLong(1, id);
            ps.setString(2, discordUserId);
            boolean ok = ps.executeUpdate() == 1;
            if (ok) {
                clearParkSlotAuxiliaryKv(discordUserId, id);
            }
            return ok;
        }
    }

    private static final String KV_PARK_KILL_DEATHS = "park_kill_deaths:";
    private static final String KV_PARK_RETRIEVE_LAST = "park_retrieve_last:";

    /** Clears kill-death counter + retrieve cooldown for a parking slot (bot_kv). */
    public void clearParkSlotAuxiliaryKv(String discordUserId, long slotId) throws SQLException {
        deleteBotKv(KV_PARK_KILL_DEATHS + discordUserId + ":" + slotId);
        deleteBotKv(KV_PARK_RETRIEVE_LAST + discordUserId + ":" + slotId);
    }

    public void clearParkKillDeathCount(String discordUserId, long slotId) throws SQLException {
        deleteBotKv(KV_PARK_KILL_DEATHS + discordUserId + ":" + slotId);
    }

    public long getParkRetrieveLastEpochSec(String discordUserId, long slotId) throws SQLException {
        return getBotKvLong(KV_PARK_RETRIEVE_LAST + discordUserId + ":" + slotId).orElse(0L);
    }

    public void setParkRetrieveLastEpochSec(String discordUserId, long slotId, long epochSec) throws SQLException {
        putBotKv(KV_PARK_RETRIEVE_LAST + discordUserId + ":" + slotId, String.valueOf(epochSec));
    }

    /**
     * Kill log purge: only the linked Discord user’s <b>session</b> slot (last park/retrieve) for this SteamID.
     *
     * @return 1 if a slot was removed, 0 if a death was counted but the slot remains, -1 if no action
     */
    public int applyPurgeOnKillForVictimSteam(String victimSteamId64, int deathsRequiredBeforeRemove) throws SQLException {
        if (victimSteamId64 == null || victimSteamId64.isBlank()) {
            return -1;
        }
        String victim = victimSteamId64.trim();
        int need = Math.max(1, deathsRequiredBeforeRemove);
        Optional<String> discordOpt = findDiscordForSteam(victim);
        if (discordOpt.isEmpty()) {
            return -1;
        }
        String discord = discordOpt.get();
        OptionalLong session = getParkSessionSlot(discord);
        if (session.isEmpty()) {
            return -1;
        }
        long slotId = session.getAsLong();
        Optional<ParkedFullRow> row = findParked(slotId, discord);
        if (row.isEmpty()) {
            return -1;
        }
        if (!victim.equals(row.get().steamId64().trim())) {
            return -1;
        }
        String k = KV_PARK_KILL_DEATHS + discord + ":" + slotId;
        int prev = 0;
        Optional<String> prevS = getBotKv(k);
        if (prevS.isPresent()) {
            try {
                prev = Integer.parseInt(prevS.get().trim());
            } catch (NumberFormatException ignored) {
                prev = 0;
            }
        }
        int next = prev + 1;
        if (next >= need) {
            if (deleteParked(slotId, discord)) {
                reconcileParkSessionAfterDelete(discord, slotId);
                return 1;
            }
            return -1;
        }
        putBotKv(k, String.valueOf(next));
        return 0;
    }

    public record ParkedRow(long id, String label, long parkedAtEpochSec, String snapshotSummary) {}

    public record ParkedFullRow(long id, String steamId64, String label, String payloadJson, long parkedAtEpochSec) {}

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

    private static final String KV_PARK_SESSION_SLOT = "park_session_slot:";

    public int countParkedSlots(String discordUserId) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM parked_dinos WHERE discord_user_id = ?")) {
            ps.setString(1, discordUserId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Purchased parking capacity beyond {@link EconomyParkingSlotsConfig#defaultSlots()}. */
    public int getExtraParkingSlots(String discordUserId) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT extra_slots FROM economy_extra_parking_slots WHERE discord_user_id = ?")) {
            ps.setString(1, discordUserId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Math.max(0, rs.getInt(1)) : 0;
            }
        }
    }

    public record ParkingSlotPurchaseResult(boolean ok, String errorMessage, int pointsSpent, int extraSlotsNow) {
        public static ParkingSlotPurchaseResult failure(String message) {
            return new ParkingSlotPurchaseResult(false, message, 0, 0);
        }

        public static ParkingSlotPurchaseResult success(int spent, int extraNow) {
            return new ParkingSlotPurchaseResult(true, "", spent, extraNow);
        }
    }

    /**
     * Buys +1 parking slot capacity (atomic balance check + increment). Caller must pass {@code cfg.enabled() == true}
     * for meaningful purchases; still validates enabled to avoid accidents.
     */
    public ParkingSlotPurchaseResult purchaseParkingExtraSlot(String discordUserId, EconomyParkingSlotsConfig cfg)
            throws SQLException {
        if (!cfg.enabled()) {
            return ParkingSlotPurchaseResult.failure("Parking slot purchases are disabled in config.");
        }
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                int extra;
                try (PreparedStatement sel = c.prepareStatement(
                        "SELECT extra_slots FROM economy_extra_parking_slots WHERE discord_user_id = ?")) {
                    sel.setString(1, discordUserId);
                    try (ResultSet rs = sel.executeQuery()) {
                        extra = rs.next() ? Math.max(0, rs.getInt(1)) : 0;
                    }
                }
                if (cfg.defaultSlots() + extra >= cfg.maxSlots()) {
                    c.rollback();
                    return ParkingSlotPurchaseResult.failure(
                            "You already have the maximum number of parking slots (**" + cfg.maxSlots() + "**).");
                }
                int cost = cfg.priceForNextExtraSlot(extra);
                try (PreparedStatement insBal = c.prepareStatement(
                        "INSERT OR IGNORE INTO economy_balance(discord_user_id, balance) VALUES(?,0)")) {
                    insBal.setString(1, discordUserId);
                    insBal.executeUpdate();
                }
                try (PreparedStatement spend = c.prepareStatement(
                        "UPDATE economy_balance SET balance = balance - ? WHERE discord_user_id = ? AND balance >= ?")) {
                    spend.setInt(1, cost);
                    spend.setString(2, discordUserId);
                    spend.setInt(3, cost);
                    if (spend.executeUpdate() != 1) {
                        int bal = readBalanceInTransaction(c, discordUserId);
                        c.rollback();
                        return ParkingSlotPurchaseResult.failure(
                                "You need **" + cost + "** points (balance **" + bal + "**).");
                    }
                }
                try (PreparedStatement up = c.prepareStatement(
                        """
                                INSERT INTO economy_extra_parking_slots(discord_user_id, extra_slots) VALUES(?,1)
                                ON CONFLICT(discord_user_id) DO UPDATE SET extra_slots = extra_slots + 1
                                """)) {
                    up.setString(1, discordUserId);
                    up.executeUpdate();
                }
                c.commit();
                return ParkingSlotPurchaseResult.success(cost, extra + 1);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private static int readBalanceInTransaction(Connection c, String discordUserId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT balance FROM economy_balance WHERE discord_user_id = ?")) {
            ps.setString(1, discordUserId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public OptionalLong latestParkedIdForDiscord(String discordUserId) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM parked_dinos WHERE discord_user_id = ? ORDER BY id DESC LIMIT 1")) {
            ps.setString(1, discordUserId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
            }
        }
    }

    /** True if this Discord account already has at least one parking slot for the given SteamID64. */
    public boolean existsParkedForDiscordAndSteam(String discordUserId, String steamId64) throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM parked_dinos WHERE discord_user_id = ? AND steam_id64 = ? LIMIT 1")) {
            ps.setString(1, discordUserId);
            ps.setString(2, steamId64);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Updates payload and timestamp for an owned slot (logout autosave).
     *
     * @return true if exactly one row updated
     */
    public boolean updateParkedPayload(long id, String discordUserId, String payloadJson, long parkedAtEpochSec)
            throws SQLException {
        try (Connection c = open();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE parked_dinos SET payload_json = ?, parked_at_epoch_sec = ?
                     WHERE id = ? AND discord_user_id = ?
                     """)) {
            ps.setString(1, payloadJson);
            ps.setLong(2, parkedAtEpochSec);
            ps.setLong(3, id);
            ps.setString(4, discordUserId);
            return ps.executeUpdate() == 1;
        }
    }

    public OptionalLong getParkSessionSlot(String discordUserId) throws SQLException {
        return getBotKvLong(KV_PARK_SESSION_SLOT + discordUserId);
    }

    public void setParkSessionSlot(String discordUserId, long slotId) throws SQLException {
        putBotKv(KV_PARK_SESSION_SLOT + discordUserId, String.valueOf(slotId));
    }

    public void clearParkSessionSlot(String discordUserId) throws SQLException {
        deleteBotKv(KV_PARK_SESSION_SLOT + discordUserId);
    }

    /** If the deleted slot was the “session” slot, point session at latest parked row or clear. */
    public void reconcileParkSessionAfterDelete(String discordUserId, long deletedId) throws SQLException {
        OptionalLong cur = getParkSessionSlot(discordUserId);
        if (cur.isEmpty() || cur.getAsLong() != deletedId) {
            return;
        }
        OptionalLong latest = latestParkedIdForDiscord(discordUserId);
        if (latest.isEmpty()) {
            clearParkSessionSlot(discordUserId);
        } else {
            setParkSessionSlot(discordUserId, latest.getAsLong());
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
