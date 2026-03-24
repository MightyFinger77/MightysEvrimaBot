package com.isle.evrima.bot.schedule;

import com.isle.evrima.bot.config.BotConfig;
import com.isle.evrima.bot.ecosystem.PopulationDashboardService;
import com.isle.evrima.bot.rcon.RconService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Optional periodic RCON {@code wipecorpses} when {@code scheduled_wipecorpses.interval_minutes} &gt; 0.
 * Can send an in-game {@code announce} a configurable number of minutes before each wipe.
 */
public final class ScheduledCorpseWipeScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduledCorpseWipeScheduler.class);
    private static final int ANNOUNCE_MAX_LEN = 400;

    private final BotConfig config;
    private final RconService rcon;
    private final PopulationDashboardService population;
    private final AtomicReference<String> runtimeEnabledMode;
    private final AtomicInteger runtimeDynamicMaxPlayers;
    private final AtomicInteger runtimeDynamicEnablePercent;
    private final AtomicInteger runtimeDynamicDisableGraceSeconds;
    private final AtomicInteger runtimeIntervalMin;
    private final AtomicInteger runtimeWarnMin;
    private final AtomicReference<String> runtimeAnnounceMessage;
    private final AtomicBoolean announcedThisCycle = new AtomicBoolean(false);
    /** In {@code dynamic} mode: after pre-wipe {@code announce} succeeds, finish this wipe even if population drops below threshold. */
    private final AtomicBoolean pendingWipeCommittedDynamic = new AtomicBoolean(false);
    private final AtomicLong nextWipeEpochSec = new AtomicLong(0L);
    private final AtomicBoolean lastEffectiveEnabled = new AtomicBoolean(false);
    private final AtomicLong dynamicAboveSinceEpochSec = new AtomicLong(0L);
    private final AtomicLong dynamicBelowSinceEpochSec = new AtomicLong(0L);

    public ScheduledCorpseWipeScheduler(BotConfig config, RconService rcon, PopulationDashboardService population) {
        this.config = Objects.requireNonNull(config, "config");
        this.rcon = Objects.requireNonNull(rcon, "rcon");
        this.population = Objects.requireNonNull(population, "population");
        this.runtimeEnabledMode = new AtomicReference<>(config.scheduledWipecorpsesEnabledMode());
        this.runtimeDynamicMaxPlayers = new AtomicInteger(Math.max(0, config.scheduledWipecorpsesDynamicMaxPlayers()));
        this.runtimeDynamicEnablePercent = new AtomicInteger(Math.max(0, Math.min(100, config.scheduledWipecorpsesDynamicEnablePercent())));
        this.runtimeDynamicDisableGraceSeconds = new AtomicInteger(Math.max(0, Math.min(600, config.scheduledWipecorpsesDynamicDisableGraceSeconds())));
        this.runtimeIntervalMin = new AtomicInteger(Math.max(0, config.scheduledWipecorpsesIntervalMinutes()));
        this.runtimeWarnMin = new AtomicInteger(Math.max(0, config.scheduledWipecorpsesWarnBeforeMinutes()));
        this.runtimeAnnounceMessage = new AtomicReference<>(config.scheduledWipecorpsesAnnounceMessage());
    }

    public void start() {
        resetCycleFromNow();
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "evrima-scheduled-wipecorpses");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(this::runTick, 5, 5, TimeUnit.SECONDS);
        LOG.info("Scheduled wipecorpses controller ready: mode={}, interval={}m, warn={}m, dynamic_max_players={}, dynamic_enable_percent={}%, dynamic_disable_grace_seconds={} (applies both directions)",
                runtimeEnabledMode.get(), runtimeIntervalMin.get(), runtimeWarnMin.get(),
                runtimeDynamicMaxPlayers.get(), runtimeDynamicEnablePercent.get(), runtimeDynamicDisableGraceSeconds.get());
    }

    public String enabledMode() {
        return runtimeEnabledMode.get();
    }

    public int dynamicMaxPlayers() {
        return runtimeDynamicMaxPlayers.get();
    }

    public int dynamicEnablePercent() {
        return runtimeDynamicEnablePercent.get();
    }

    public int dynamicDisableGraceSeconds() {
        return runtimeDynamicDisableGraceSeconds.get();
    }

    public boolean isEffectivelyEnabled() {
        String mode = runtimeEnabledMode.get();
        if ("true".equals(mode)) {
            return true;
        }
        if (!"dynamic".equals(mode)) {
            return false;
        }
        int max = runtimeDynamicMaxPlayers.get();
        if (max <= 0) {
            return false;
        }
        int pct = runtimeDynamicEnablePercent.get();
        int graceSec = runtimeDynamicDisableGraceSeconds.get();
        long now = nowEpochSec();
        try {
            int players = Math.max(0, population.snapshot(false).data().referencePlayerTotal());
            int fill = (int) Math.min(100L, Math.floor(100.0 * (double) players / (double) max));
            boolean atOrAbove = fill >= pct;
            boolean currentlyEnabled = lastEffectiveEnabled.get();
            if (graceSec <= 0) {
                dynamicAboveSinceEpochSec.set(0L);
                dynamicBelowSinceEpochSec.set(0L);
                return atOrAbove;
            }
            if (atOrAbove) {
                dynamicBelowSinceEpochSec.set(0L);
                if (currentlyEnabled) {
                    dynamicAboveSinceEpochSec.set(0L);
                    return true;
                }
                long aboveSince = dynamicAboveSinceEpochSec.get();
                if (aboveSince <= 0L) {
                    dynamicAboveSinceEpochSec.compareAndSet(0L, now);
                    return false;
                }
                return (now - aboveSince) >= graceSec;
            }
            dynamicAboveSinceEpochSec.set(0L);
            if (!currentlyEnabled) {
                dynamicBelowSinceEpochSec.set(0L);
                return false;
            }
            long belowSince = dynamicBelowSinceEpochSec.get();
            if (belowSince <= 0L) {
                dynamicBelowSinceEpochSec.compareAndSet(0L, now);
                return true;
            }
            return (now - belowSince) < graceSec;
        } catch (Exception e) {
            LOG.warn("Scheduled wipecorpses dynamic mode: population check failed: {}", e.toString());
            return false;
        }
    }

    public void setEnabledMode(String mode) {
        if (mode == null) {
            mode = "false";
        }
        String m = mode.trim().toLowerCase();
        if (!"true".equals(m) && !"false".equals(m) && !"dynamic".equals(m)) {
            throw new IllegalArgumentException("enabled mode must be true, false, or dynamic");
        }
        runtimeEnabledMode.set(m);
        dynamicAboveSinceEpochSec.set(0L);
        dynamicBelowSinceEpochSec.set(0L);
        resetCycleFromNow();
    }

    public int intervalMinutes() {
        return runtimeIntervalMin.get();
    }

    public int warnBeforeMinutes() {
        return runtimeWarnMin.get();
    }

    public String announceMessage() {
        return runtimeAnnounceMessage.get();
    }

    public void setIntervalMinutes(int minutes) {
        runtimeIntervalMin.set(Math.max(0, Math.min(10_080, minutes)));
        resetCycleFromNow();
    }

    public void setWarnBeforeMinutes(int minutes) {
        runtimeWarnMin.set(Math.max(0, Math.min(1_440, minutes)));
        resetCycleFromNow();
    }

    public void setDynamicMaxPlayers(int maxPlayers) {
        runtimeDynamicMaxPlayers.set(Math.max(0, Math.min(1000, maxPlayers)));
        dynamicAboveSinceEpochSec.set(0L);
        dynamicBelowSinceEpochSec.set(0L);
        resetCycleFromNow();
    }

    public void setDynamicEnablePercent(int percent) {
        runtimeDynamicEnablePercent.set(Math.max(0, Math.min(100, percent)));
        dynamicAboveSinceEpochSec.set(0L);
        dynamicBelowSinceEpochSec.set(0L);
        resetCycleFromNow();
    }

    public void setDynamicDisableGraceSeconds(int seconds) {
        runtimeDynamicDisableGraceSeconds.set(Math.max(0, Math.min(600, seconds)));
        dynamicAboveSinceEpochSec.set(0L);
        dynamicBelowSinceEpochSec.set(0L);
        resetCycleFromNow();
    }

    public void setAnnounceMessage(String msg) {
        runtimeAnnounceMessage.set(msg == null ? "" : msg);
        // no cycle reset needed for text change only
    }

    private void resetCycleFromNow() {
        long now = nowEpochSec();
        long intervalSec = Math.max(0L, runtimeIntervalMin.get()) * 60L;
        nextWipeEpochSec.set(intervalSec <= 0 ? 0L : now + intervalSec);
        announcedThisCycle.set(false);
        pendingWipeCommittedDynamic.set(false);
    }

    private void runTick() {
        boolean effectiveEnabled = isEffectivelyEnabled();
        boolean wipeCommitted = pendingWipeCommittedDynamic.get();
        boolean prev = lastEffectiveEnabled.getAndSet(effectiveEnabled);
        if (effectiveEnabled != prev && !wipeCommitted) {
            resetCycleFromNow();
        }
        if (!effectiveEnabled && !wipeCommitted) {
            return;
        }
        int periodMin = runtimeIntervalMin.get();
        if (periodMin <= 0) {
            return;
        }
        int warnMin = runtimeWarnMin.get();
        boolean useWarning = warnMin > 0 && periodMin > warnMin;
        long now = nowEpochSec();
        long wipeAt = nextWipeEpochSec.get();
        if (wipeAt <= 0L) {
            resetCycleFromNow();
            return;
        }

        try {
            if (useWarning && !announcedThisCycle.get()) {
                long announceAt = wipeAt - warnMin * 60L;
                if (now >= announceAt) {
                    String msg = sanitizeAnnounce(runtimeAnnounceMessage.get());
                    String annOut = rcon.run("announce " + msg);
                    announcedThisCycle.set(true);
                    if ("dynamic".equals(runtimeEnabledMode.get())) {
                        pendingWipeCommittedDynamic.set(true);
                        LOG.info("Scheduled wipecorpses: pre-wipe announce sent ({} min to wipe); dynamic latch on — wipe will run on schedule even if population drops: {}",
                                warnMin, oneLine(annOut));
                    } else {
                        LOG.info("Scheduled wipecorpses: pre-wipe announce sent ({} min to wipe): {}",
                                warnMin, oneLine(annOut));
                    }
                }
            }
            if (now >= wipeAt) {
                String out = rcon.run("wipecorpses");
                LOG.info("Scheduled wipecorpses OK: {}", oneLine(out));
                resetCycleFromNow();
            }
        } catch (IOException e) {
            LOG.warn("Scheduled wipecorpses failed: {}", e.toString());
        } catch (Exception e) {
            LOG.warn("Scheduled wipecorpses error: {}", e.toString());
        }
    }

    private static long nowEpochSec() {
        return System.currentTimeMillis() / 1000L;
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
