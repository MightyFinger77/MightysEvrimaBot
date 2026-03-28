package com.isle.evrima.bot.dino;

import com.isle.evrima.bot.config.BotConfig;
import com.isle.evrima.bot.config.DinoParkLogoutAutosaveConfig;
import com.isle.evrima.bot.config.LiveBotConfig;
import com.isle.evrima.bot.db.Database;
import com.isle.evrima.bot.rcon.EvrimaRcon;
import com.isle.evrima.bot.rcon.RconService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Refreshes an <b>existing</b> parking slot from live server state when TheIsle.log lines match.
 * Only runs for SteamIDs whose linked Discord user already has a {@code parked_dinos} row for that SteamID
 * (no scheduling or RCON for players who have never used {@code /evrima dino park} for this character).
 */
public final class DinoParkLogoutAutosave {

    private static final Logger LOG = LoggerFactory.getLogger(DinoParkLogoutAutosave.class);
    private static final Pattern STEAM = Pattern.compile("(7656119\\d{10})");
    /** When {@code hard_disconnect_delay_seconds} is 0 in config, hard disconnect still waits this long before refresh. */
    private static final int HARD_DELAY_FALLBACK_SECONDS = 300;

    private static final ScheduledExecutorService HARD_DELAY_SCHED = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "evrima-dino-hard-logout-delay");
        t.setDaemon(true);
        return t;
    });

    private static final ConcurrentHashMap<String, ScheduledFuture<?>> PENDING_HARD_DELAY = new ConcurrentHashMap<>();

    private DinoParkLogoutAutosave() {}

    public static void handleLogLine(String rawLine, LiveBotConfig live, Database db, RconService rcon) {
        DinoParkLogoutAutosaveConfig ac = live.get().dinoParkLogoutAutosave();
        if (!ac.enabled()) {
            return;
        }
        if (rawLine == null || rawLine.isBlank()) {
            return;
        }
        Optional<String> steamOpt = firstSteam64(rawLine);
        boolean soft = steamOpt.isPresent() && lineMatchesAny(rawLine, ac.softLogoutLineContainsAny());
        boolean hard = lineMatchesAny(rawLine, ac.hardDisconnectLineContainsAny());

        try {
            if (soft) {
                String steam = steamOpt.get();
                cancelHardDelay(steam);
                if (!eligibleForLogoutAutosave(db, steam)) {
                    return;
                }
                tryRefreshSlot(steam, live.get(), db, rcon);
            }

            if (hard && !ac.hardDisconnectLineContainsAny().isEmpty()) {
                if (steamOpt.isEmpty()) {
                    LOG.debug("logout_autosave: hard disconnect line has no SteamID64 — cannot schedule autosave ({})",
                            truncateForLog(rawLine, 120));
                    return;
                }
                String steam = steamOpt.get();
                if (!eligibleForLogoutAutosave(db, steam)) {
                    return;
                }
                int delaySec = ac.hardDisconnectDelaySeconds() > 0
                        ? ac.hardDisconnectDelaySeconds()
                        : HARD_DELAY_FALLBACK_SECONDS;
                scheduleHardDelay(steam, live, db, rcon, delaySec);
            }
        } catch (SQLException | IOException e) {
            LOG.warn("dino_park logout_autosave: {}", e.toString());
        }
    }

    private static String truncateForLog(String s, int max) {
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    private static void cancelHardDelay(String steamId64) {
        ScheduledFuture<?> f = PENDING_HARD_DELAY.remove(steamId64);
        if (f != null) {
            f.cancel(false);
        }
    }

    private static void scheduleHardDelay(String steam, LiveBotConfig live, Database db, RconService rcon, int delaySec) {
        ScheduledFuture<?> prev = PENDING_HARD_DELAY.remove(steam);
        if (prev != null) {
            prev.cancel(false);
        }
        final ScheduledFuture<?>[] holder = new ScheduledFuture<?>[1];
        holder[0] = HARD_DELAY_SCHED.schedule(() -> {
            try {
                ScheduledFuture<?> cur = PENDING_HARD_DELAY.get(steam);
                if (cur != holder[0]) {
                    return;
                }
                PENDING_HARD_DELAY.remove(steam);
                BotConfig cfg = live.get();
                if (!cfg.dinoParkLogoutAutosave().enabled()) {
                    return;
                }
                if (!eligibleForLogoutAutosave(db, steam)) {
                    return;
                }
                tryRefreshSlot(steam, cfg, db, rcon);
            } catch (SQLException | IOException e) {
                LOG.warn("dino_park logout_autosave (delayed hard): {}", e.toString());
            }
        }, delaySec, TimeUnit.SECONDS);
        PENDING_HARD_DELAY.put(steam, holder[0]);
        LOG.info("dino_park: hard-disconnect autosave scheduled for Steam {} in {}s", steam, delaySec);
    }

    private static Optional<String> firstSteam64(String line) {
        Matcher m = STEAM.matcher(line);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** Linked Discord account must already have a parking slot stored for this SteamID. */
    private static boolean eligibleForLogoutAutosave(Database db, String steamId64) throws SQLException {
        Optional<String> discord = db.findDiscordForSteam(steamId64);
        if (discord.isEmpty()) {
            return false;
        }
        return db.existsParkedForDiscordAndSteam(discord.get(), steamId64);
    }

    private static boolean lineMatchesAny(String raw, List<String> markers) {
        if (markers == null || markers.isEmpty()) {
            return false;
        }
        for (String m : markers) {
            if (m != null && !m.isBlank() && raw.contains(m)) {
                return true;
            }
        }
        return false;
    }

    private static void tryRefreshSlot(String steamId64, BotConfig cfg, Database db, RconService rcon)
            throws SQLException, IOException {
        Optional<String> discord = db.findDiscordForSteam(steamId64);
        if (discord.isEmpty()) {
            return;
        }
        String uid = discord.get();
        OptionalLong session = db.getParkSessionSlot(uid);
        if (session.isEmpty()) {
            return;
        }
        long slotId = session.getAsLong();
        Optional<Database.ParkedFullRow> full = db.findParked(slotId, uid);
        if (full.isEmpty() || !steamId64.equals(full.get().steamId64())) {
            OptionalLong latest = db.latestParkedIdForDiscord(uid);
            if (latest.isEmpty()) {
                db.clearParkSessionSlot(uid);
                return;
            }
            slotId = latest.getAsLong();
            full = db.findParked(slotId, uid);
            if (full.isEmpty() || !steamId64.equals(full.get().steamId64())) {
                return;
            }
            db.setParkSessionSlot(uid, slotId);
        }

        String raw = rcon.run("getplayerdata " + steamId64);
        String filtered = EvrimaRcon.filterGetplayerdataResponseForSteamId(raw, steamId64);
        Optional<byte[]> disk = PlayerdataFileRestore.captureIfPresent(cfg.dinoParkPlayerdataFile(), cfg.configYamlPath(), steamId64);

        long now = Instant.now().getEpochSecond();
        String json;
        if (filtered.isBlank()) {
            if (disk.isEmpty()) {
                LOG.debug("logout_autosave: no getplayerdata row and no disk capture for {}", steamId64);
                return;
            }
            ParkedDinoPayload.Summary sum = new ParkedDinoPayload.Summary(null, null, null);
            json = ParkedDinoPayload.buildJson(now,
                    "(autosave: player not in getplayerdata; disk snapshot only)",
                    sum,
                    disk);
        } else {
            ParkedDinoPayload.Summary sum = ParkedDinoPayload.parseSummaryFromGetplayerdata(filtered);
            json = ParkedDinoPayload.buildJson(now, filtered, sum, disk);
        }

        if (!db.updateParkedPayload(slotId, uid, json, now)) {
            LOG.debug("logout_autosave: slot {} not updated (race or missing row)", slotId);
            return;
        }
        db.appendAudit(uid, "dino_park_logout_autosave", String.valueOf(slotId));
        LOG.info("dino_park: logout_autosave refreshed slot {} for Steam {}", slotId, steamId64);
    }
}
