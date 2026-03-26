package com.isle.evrima.bot.schedule;

import com.isle.evrima.bot.config.AdaptiveAiDensityBandMode;
import com.isle.evrima.bot.config.AdaptiveAiDensityTier;
import com.isle.evrima.bot.config.BotConfig;
import com.isle.evrima.bot.config.LiveBotConfig;
import com.isle.evrima.bot.db.Database;
import com.isle.evrima.bot.ecosystem.PopulationDashboardService;
import com.isle.evrima.bot.rcon.EvrimaRcon;
import com.isle.evrima.bot.rcon.RconService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sets RCON {@code aidensity} from configured population tiers. Bounds are either fill % of {@code max_players}
 * ({@link AdaptiveAiDensityBandMode#PERCENT}) or raw online player counts ({@link AdaptiveAiDensityBandMode#AMOUNT}).
 */
public final class AdaptiveAiDensityScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(AdaptiveAiDensityScheduler.class);
    private static final String KV_LAST_DENSITY = "adaptive_ai_density_last_applied";
    private static final double EPS = 1e-5;

    private final LiveBotConfig live;
    private final RconService rcon;
    private final PopulationDashboardService population;
    private final Database database;
    private final RconWriteGuard rconGuard;
    private final AtomicBoolean warnedNoTier = new AtomicBoolean(false);

    public AdaptiveAiDensityScheduler(
            LiveBotConfig live,
            RconService rcon,
            PopulationDashboardService population,
            Database database,
            RconWriteGuard rconGuard) {
        this.live = Objects.requireNonNull(live, "live");
        this.rcon = Objects.requireNonNull(rcon, "rcon");
        this.population = Objects.requireNonNull(population, "population");
        this.database = Objects.requireNonNull(database, "database");
        this.rconGuard = Objects.requireNonNull(rconGuard, "rconGuard");
    }

    public void start() {
        BotConfig config = live.get();
        if (!config.adaptiveAiDensityEnabled()) {
            return;
        }
        AdaptiveAiDensityBandMode mode = config.adaptiveAiDensityBandMode();
        int max = config.adaptiveAiDensityMaxPlayers();
        if (mode == AdaptiveAiDensityBandMode.PERCENT && max <= 0) {
            LOG.warn("adaptive_ai_density: enabled (percent mode) but max_players invalid — scheduler not started");
            return;
        }
        int minutes = Math.max(1, config.adaptiveAiDensityIntervalMinutes());
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "evrima-adaptive-ai-density");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleAtFixedRate(this::runSafe, 58, minutes * 60L, TimeUnit.SECONDS);
        if (mode == AdaptiveAiDensityBandMode.PERCENT) {
            LOG.info("Adaptive AI density: every {} min (band_mode=percent, max_players={}, {} tier(s))",
                    minutes, max, config.adaptiveAiDensityTiers().size());
        } else {
            LOG.info("Adaptive AI density: every {} min (band_mode=amount, by online player count; max_players={} unused for tiers, {} tier(s))",
                    minutes, max, config.adaptiveAiDensityTiers().size());
        }
    }

    private void runSafe() {
        try {
            runOnce();
        } catch (SQLException | IOException e) {
            rconGuard.observeFailure();
            LOG.warn("adaptive_ai_density tick failed: {}", e.toString());
        } catch (Exception e) {
            rconGuard.observeFailure();
            LOG.warn("adaptive_ai_density tick failed: {}", e.toString());
        }
    }

    private void runOnce() throws SQLException, IOException {
        BotConfig config = live.get();
        if (!config.adaptiveAiDensityEnabled()) {
            return;
        }
        AdaptiveAiDensityBandMode mode = config.adaptiveAiDensityBandMode();
        int maxPlayers = config.adaptiveAiDensityMaxPlayers();
        if (mode == AdaptiveAiDensityBandMode.PERCENT && maxPlayers <= 0) {
            return;
        }

        PopulationDashboardService.SnapshotResult res = population.snapshot(true);
        int players = Math.max(0, res.data().referencePlayerTotal());
        rconGuard.observeHealthy();

        List<AdaptiveAiDensityTier> tiers = config.adaptiveAiDensityTiers();
        Optional<AdaptiveAiDensityTier> tier;
        int fillPct = 0;
        if (mode == AdaptiveAiDensityBandMode.PERCENT) {
            fillPct = (int) Math.min(100L, Math.floor(100.0 * (double) players / (double) maxPlayers));
            tier = AdaptiveAiDensityTier.matchPercent(fillPct, tiers);
            if (tier.isEmpty()) {
                if (warnedNoTier.compareAndSet(false, true)) {
                    LOG.warn("adaptive_ai_density: no tier matched fill {}% (check tiers cover 0–100)", fillPct);
                }
                return;
            }
        } else {
            tier = AdaptiveAiDensityTier.matchPlayers(players, tiers);
            if (tier.isEmpty()) {
                if (warnedNoTier.compareAndSet(false, true)) {
                    LOG.warn(
                            "adaptive_ai_density: no tier matched {} online players (amount mode — extend tier max_players)",
                            players);
                }
                return;
            }
        }
        warnedNoTier.set(false);

        AdaptiveAiDensityTier band = tier.get();
        double target = band.density();
        Optional<String> prev = database.getBotKv(KV_LAST_DENSITY);
        if (prev.isPresent()) {
            try {
                double last = Double.parseDouble(prev.get().trim());
                if (Math.abs(last - target) < EPS) {
                    return;
                }
            } catch (NumberFormatException ignored) {
                // apply fresh
            }
        }

        if (!rconGuard.allowSchedulerWrite("adaptive_ai_density", players)) {
            return;
        }
        rcon.run(EvrimaRcon.lineAidensity(target));
        database.putBotKv(KV_LAST_DENSITY, BigDecimal.valueOf(target).stripTrailingZeros().toPlainString());
        if (mode == AdaptiveAiDensityBandMode.PERCENT) {
            LOG.info(
                    "adaptive_ai_density (scheduler, automatic): AI density changed — fill {}% (tier {}–{}% → multiplier {}) — "
                            + "{} players / max {} — RCON {}",
                    fillPct,
                    band.minInclusive(),
                    band.maxInclusive(),
                    BigDecimal.valueOf(target).stripTrailingZeros().toPlainString(),
                    players,
                    maxPlayers,
                    EvrimaRcon.lineAidensity(target));
        } else {
            LOG.info(
                    "adaptive_ai_density (scheduler, automatic): AI density changed — {} players (tier {}–{} → multiplier {}) — RCON {}",
                    players,
                    band.minInclusive(),
                    band.maxInclusive(),
                    BigDecimal.valueOf(target).stripTrailingZeros().toPlainString(),
                    EvrimaRcon.lineAidensity(target));
        }
    }
}
