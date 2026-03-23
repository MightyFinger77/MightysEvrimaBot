package com.isle.evrima.bot.schedule;

import com.isle.evrima.bot.config.BotConfig;
import com.isle.evrima.bot.rcon.RconService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Optional periodic RCON {@code wipecorpses} when {@code scheduled_wipecorpses.interval_minutes} &gt; 0.
 * Can send an in-game {@code announce} a configurable number of minutes before each wipe.
 */
public final class ScheduledCorpseWipeScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduledCorpseWipeScheduler.class);
    private static final int ANNOUNCE_MAX_LEN = 400;

    private final BotConfig config;
    private final RconService rcon;

    public ScheduledCorpseWipeScheduler(BotConfig config, RconService rcon) {
        this.config = Objects.requireNonNull(config, "config");
        this.rcon = Objects.requireNonNull(rcon, "rcon");
    }

    public void start() {
        int periodMin = config.scheduledWipecorpsesIntervalMinutes();
        if (periodMin <= 0) {
            return;
        }
        int warnMin = config.scheduledWipecorpsesWarnBeforeMinutes();
        long periodSec = periodMin * 60L;

        long delayBetweenCyclesSec;
        long initialDelaySec;
        boolean useWarning = warnMin > 0 && periodMin > warnMin;
        if (useWarning) {
            // First announce at (interval - warn) minutes after JVM start, then wipe after warn minutes.
            // Repeats: delay after wipe = same gap so the next announce is one full interval after the previous announce.
            long gapSec = (long) (periodMin - warnMin) * 60L;
            initialDelaySec = gapSec;
            delayBetweenCyclesSec = gapSec;
        } else {
            delayBetweenCyclesSec = periodSec;
            // First wipe after one full interval from process start (no 2-minute shortcut).
            initialDelaySec = periodSec;
            if (warnMin > 0 && periodMin <= warnMin) {
                LOG.warn("scheduled_wipecorpses: interval_minutes ({}) must be > warn_before_minutes ({}) for a timed warning; "
                        + "running wipecorpses only (no pre-announce).", periodMin, warnMin);
            }
        }

        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "evrima-scheduled-wipecorpses");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(this::runCycle, initialDelaySec, delayBetweenCyclesSec, TimeUnit.SECONDS);

        if (useWarning) {
            LOG.info("Scheduled wipecorpses: interval {} min, warn {} min before wipe — "
                            + "first in-game announce in {}s (~{} min), then ~{} min idle after each wipe until next announce.",
                    periodMin, warnMin, initialDelaySec, initialDelaySec / 60, delayBetweenCyclesSec / 60);
        } else {
            LOG.info("Scheduled wipecorpses: every {} minute(s) (no pre-announce); first run in {}s (~{} min)",
                    periodMin, initialDelaySec, initialDelaySec / 60);
        }
    }

    private void runCycle() {
        int periodMin = config.scheduledWipecorpsesIntervalMinutes();
        int warnMin = config.scheduledWipecorpsesWarnBeforeMinutes();
        boolean useWarning = warnMin > 0 && periodMin > warnMin;
        try {
            if (useWarning) {
                String msg = sanitizeAnnounce(config.scheduledWipecorpsesAnnounceMessage());
                String annOut = rcon.run("announce " + msg);
                LOG.info("Scheduled wipecorpses: pre-wipe announce sent ({} min to wipe): {}",
                        warnMin, oneLine(annOut));
                sleepMinutes(warnMin);
            }
            String out = rcon.run("wipecorpses");
            LOG.info("Scheduled wipecorpses OK: {}", oneLine(out));
        } catch (IOException e) {
            LOG.warn("Scheduled wipecorpses failed: {}", e.toString());
        } catch (Exception e) {
            LOG.warn("Scheduled wipecorpses error: {}", e.toString());
        }
    }

    private static void sleepMinutes(int minutes) {
        try {
            TimeUnit.MINUTES.sleep(minutes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Scheduled wipecorpses wait interrupted");
        }
    }

    private static String sanitizeAnnounce(String raw) {
        String s = raw == null ? "" : raw.replace('\r', ' ').replace('\n', ' ').trim();
        if (s.length() > ANNOUNCE_MAX_LEN) {
            s = s.substring(0, ANNOUNCE_MAX_LEN);
        }
        return s.isEmpty() ? "Corpse wipe soon." : s;
    }

    private static String oneLine(String out) {
        String oneLine = out == null ? "" : out.replace('\r', ' ').replace('\n', ' ').trim();
        if (oneLine.length() > 300) {
            oneLine = oneLine.substring(0, 297) + "…";
        }
        return oneLine.isEmpty() ? "(empty response)" : oneLine;
    }
}
