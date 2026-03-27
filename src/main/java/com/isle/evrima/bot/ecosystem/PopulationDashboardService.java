package com.isle.evrima.bot.ecosystem;

import com.isle.evrima.bot.config.LiveBotConfig;
import com.isle.evrima.bot.rcon.RconService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Fetches RCON playerlist, parses species buckets, and caches results to avoid hammering RCON.
 */
public final class PopulationDashboardService {

    private static final Logger LOG = LoggerFactory.getLogger(PopulationDashboardService.class);
    private static final long EMPTY_STANDBY_MIN_MS = 45_000L;
    private static final long DOWN_STANDBY_BASE_MS = 15_000L;
    private static final long DOWN_STANDBY_MAX_MS = 300_000L;

    private final LiveBotConfig live;
    private final RconService rcon;
    /** Replaced by {@link #reloadTaxonomyFromDisk()} when admins reload YAML from disk. */
    private volatile SpeciesTaxonomy taxonomy;

    private volatile Cached cache;
    private volatile long standbyUntilEpochMs;
    private volatile int consecutiveEmptyPolls;
    private volatile int consecutiveFailures;

    private record Cached(long fetchedAtEpochMs, PopulationSnapshot snapshot) {}

    public record SnapshotResult(PopulationSnapshot data, boolean fromCache, long cacheAgeSeconds) {}

    public PopulationDashboardService(LiveBotConfig live, RconService rcon, SpeciesTaxonomy taxonomy) {
        this.live = Objects.requireNonNull(live, "live");
        this.rcon = Objects.requireNonNull(rcon, "rcon");
        this.taxonomy = Objects.requireNonNull(taxonomy, "taxonomy");
    }

    public SpeciesTaxonomy taxonomy() {
        return taxonomy;
    }

    /**
     * Reloads {@code species-taxonomy.yml} next to {@link LiveBotConfig#yamlPath()} and clears the ecosystem snapshot cache.
     */
    public void reloadTaxonomyFromDisk() throws IOException {
        SpeciesTaxonomy next = loadTaxonomy(live.yamlPath());
        this.taxonomy = next;
        this.cache = null;
        LOG.info("Reloaded species taxonomy from disk (ecosystem cache cleared)");
    }

    /**
     * Loads {@code species-taxonomy.yml} from the same directory as {@code config.yml} (e.g. {@code configs/}).
     */
    public static SpeciesTaxonomy loadTaxonomy(Path configYamlPath) throws IOException {
        Path parent = configYamlPath.getParent();
        if (parent == null) {
            parent = Path.of(".").toAbsolutePath().normalize();
        }
        Path taxFile = parent.resolve("species-taxonomy.yml").normalize();
        if (!Files.isRegularFile(taxFile)) {
            throw new IOException("Missing species-taxonomy.yml next to config (expected " + taxFile.toAbsolutePath() + ")");
        }
        LOG.info("Loading species taxonomy from {}", taxFile.toAbsolutePath());
        return SpeciesTaxonomy.loadFile(taxFile);
    }

    /**
     * @param forceRefresh bypass TTL and hit RCON
     */
    public SnapshotResult snapshot(boolean forceRefresh) throws IOException {
        long ttlMs = Math.max(5_000L, live.get().ecosystemCacheTtlSeconds() * 1000L);
        long now = System.currentTimeMillis();
        Cached c = cache;
        if (!forceRefresh && c != null && now - c.fetchedAtEpochMs < ttlMs) {
            long ageSec = Math.max(0, (now - c.fetchedAtEpochMs) / 1000);
            return new SnapshotResult(c.snapshot, true, ageSec);
        }
        if (now < standbyUntilEpochMs) {
            if (c != null) {
                long ageSec = Math.max(0, (now - c.fetchedAtEpochMs) / 1000);
                return new SnapshotResult(c.snapshot, true, ageSec);
            }
            return new SnapshotResult(standbySnapshot("Population standby: waiting before next RCON poll."), true, 0);
        }

        PopulationSnapshot snap;
        try {
            String raw = rcon.run("playerlist");
            snap = PlayerlistPopulationParser.parse(raw, taxonomy);
            if (PlayerlistPopulationParser.shouldFetchBulkGetplayerdata(raw, snap)) {
                try {
                    String bulk = rcon.run("getplayerdata");
                    snap = PlayerlistPopulationParser.parse(raw, bulk, taxonomy);
                } catch (IOException e) {
                    LOG.warn("ecosystem: bulk getplayerdata failed: {}", e.toString());
                }
            }
        } catch (IOException e) {
            consecutiveFailures++;
            long backoffMs = failureBackoffMs(consecutiveFailures);
            standbyUntilEpochMs = now + backoffMs;
            if (consecutiveFailures == 1) {
                LOG.warn("ecosystem: playerlist unavailable; entering standby for {}s", backoffMs / 1000L);
            } else {
                LOG.debug("ecosystem: playerlist still unavailable; standby {}s", backoffMs / 1000L);
            }
            if (c != null) {
                long ageSec = Math.max(0, (now - c.fetchedAtEpochMs) / 1000);
                return new SnapshotResult(c.snapshot, true, ageSec);
            }
            return new SnapshotResult(standbySnapshot("Population standby: server unreachable."), true, 0);
        }

        consecutiveFailures = 0;
        if (snap.referencePlayerTotal() <= 0) {
            consecutiveEmptyPolls++;
            long holdMs = Math.max(ttlMs, EMPTY_STANDBY_MIN_MS);
            standbyUntilEpochMs = now + holdMs;
            if (consecutiveEmptyPolls == 1) {
                LOG.info("ecosystem: server appears empty; standby polling for {}s", holdMs / 1000L);
            }
        } else {
            consecutiveEmptyPolls = 0;
            standbyUntilEpochMs = 0L;
        }
        cache = new Cached(now, snap);
        return new SnapshotResult(snap, false, 0);
    }

    private static long failureBackoffMs(int failures) {
        if (failures <= 1) {
            return DOWN_STANDBY_BASE_MS;
        }
        long backoff = DOWN_STANDBY_BASE_MS;
        for (int i = 1; i < failures; i++) {
            backoff = Math.min(DOWN_STANDBY_MAX_MS, backoff * 2L);
            if (backoff >= DOWN_STANDBY_MAX_MS) {
                return DOWN_STANDBY_MAX_MS;
            }
        }
        return backoff;
    }

    private static PopulationSnapshot standbySnapshot(String note) {
        return new PopulationSnapshot(
                0,
                0,
                0,
                0,
                java.util.Map.of(),
                0,
                0,
                0,
                0,
                "None",
                "None",
                "None",
                note,
                ""
        );
    }
}
