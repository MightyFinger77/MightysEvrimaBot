package com.isle.evrima.bot.schedule;

import com.isle.evrima.bot.config.BotConfig;
import com.isle.evrima.bot.config.LiveBotConfig;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional periodic RCON {@code wipecorpses} when {@code scheduled_wipecorpses.interval_minutes} &gt; 0.
 * Settings are read from {@code config.yml} via {@link LiveBotConfig} (reloaded after admin slash commands).
 */
public final class ScheduledCorpseWipeScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduledCorpseWipeScheduler.class);
    private static final int ANNOUNCE_MAX_LEN = 400;

    private final LiveBotConfig live;
    private final RconService rcon;
    private final PopulationDashboardService population;
    private final RconWriteGuard rconGuard;
    private final AtomicBoolean announcedThisCycle = new AtomicBoolean(false);
    /** In {@code dynamic} mode: after pre-wipe {@code announce} succeeds, finish this wipe even if population drops below threshold. */
    private final AtomicBoolean pendingWipeCommittedDynamic = new AtomicBoolean(false);
    private final AtomicLong nextWipeEpochSec = new AtomicLong(0L);
    private final AtomicBoolean lastEffectiveEnabled = new AtomicBoolean(false);
    private final AtomicLong dynamicAboveSinceEpochSec = new AtomicLong(0L);
    private final AtomicLong dynamicBelowSinceEpochSec = new AtomicLong(0L);

    public ScheduledCorpseWipeScheduler(
            LiveBotConfig live,
            RconService rcon,
            PopulationDashboardService population,
            RconWriteGuard rconGuard) {
        this.live = Objects.requireNonNull(live, "live");
        this.rcon = Objects.requireNonNull(rcon, "rcon");
        this.population = Objects.requireNonNull(population, "population");
        this.rconGuard = Objects.requireNonNull(rconGuard, "rconGuard");
    }

    private BotConfig cfg() {
        return live.get();
    }

    public void start() {
        resetCycleFromNow();
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "evrima-scheduled-wipecorpses");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(this::runTick, 5, 5, TimeUnit.SECONDS);
        BotConfig c = cfg();
        LOG.info("Scheduled wipecorpses controller ready: mode={}, interval={}m, warn={}m, dynamic_max_players={}, dynamic_enable_percent={}%, dynamic_disable_grace_seconds={} (applies both directions)",
                c.scheduledWipecorpsesEnabledMode(), c.scheduledWipecorpsesIntervalMinutes(), c.scheduledWipecorpsesWarnBeforeMinutes(),
                c.scheduledWipecorpsesDynamicMaxPlayers(), c.scheduledWipecorpsesDynamicEnablePercent(), c.scheduledWipecorpsesDynamicDisableGraceSeconds());
    }

    /** After {@code config.yml} is rewritten and {@link LiveBotConfig#reloadFromDisk()} completes. */
    public void onConfigReloaded() {
        dynamicAboveSinceEpochSec.set(0L);
        dynamicBelowSinceEpochSec.set(0L);
        resetCycleFromNow();
    }

    public String enabledMode() {
        return cfg().scheduledWipecorpsesEnabledMode();
    }

    public int dynamicMaxPlayers() {
        return cfg().scheduledWipecorpsesDynamicMaxPlayers();
    }

    public int dynamicEnablePercent() {
        return cfg().scheduledWipecorpsesDynamicEnablePercent();
    }

    public int dynamicDisableGraceSeconds() {
        return cfg().scheduledWipecorpsesDynamicDisableGraceSeconds();
    }

    public boolean isEffectivelyEnabled() {
        BotConfig c = cfg();
        String mode = c.scheduledWipecorpsesEnabledMode();
        if ("true".equals(mode)) {
            return true;
        }
        if (!"dynamic".equals(mode)) {
            return false;
        }
        int max = c.scheduledWipecorpsesDynamicMaxPlayers();
        if (max <= 0) {
            return false;
        }
        int pct = c.scheduledWipecorpsesDynamicEnablePercent();
        int graceSec = c.scheduledWipecorpsesDynamicDisableGraceSeconds();
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

    public int intervalMinutes() {
        return cfg().scheduledWipecorpsesIntervalMinutes();
    }

    public int warnBeforeMinutes() {
        return cfg().scheduledWipecorpsesWarnBeforeMinutes();
    }

    public String announceMessage() {
        return cfg().scheduledWipecorpsesAnnounceMessage();
    }

    private void resetCycleFromNow() {
        long now = nowEpochSec();
        long intervalSec = Math.max(0L, cfg().scheduledWipecorpsesIntervalMinutes()) * 60L;
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
        BotConfig c = cfg();
        int periodMin = c.scheduledWipecorpsesIntervalMinutes();
        if (periodMin <= 0) {
            return;
        }
        int warnMin = c.scheduledWipecorpsesWarnBeforeMinutes();
        boolean useWarning = warnMin > 0 && periodMin > warnMin;
        long now = nowEpochSec();
        long wipeAt = nextWipeEpochSec.get();
        if (wipeAt <= 0L) {
            resetCycleFromNow();
            return;
        }

        try {
            int players = Math.max(0, population.snapshot(false).data().referencePlayerTotal());
            rconGuard.observeHealthy();
            if (!rconGuard.allowSchedulerWrite("scheduled_wipecorpses", players)) {
                return;
            }
            if (useWarning && !announcedThisCycle.get()) {
                long announceAt = wipeAt - warnMin * 60L;
                if (now >= announceAt) {
                    String msg = sanitizeAnnounce(c.scheduledWipecorpsesAnnounceMessage());
                    String annOut = rcon.run("announce " + msg);
                    announcedThisCycle.set(true);
                    if ("dynamic".equals(c.scheduledWipecorpsesEnabledMode())) {
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
            rconGuard.observeFailure();
            LOG.warn("Scheduled wipecorpses failed: {}", e.toString());
        } catch (Exception e) {
            rconGuard.observeFailure();
            LOG.warn("Scheduled wipecorpses error: {}", e.toString());
        }
    }

    private static long nowEpochSec() {
        return System.currentTimeMillis() / 1000;
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
