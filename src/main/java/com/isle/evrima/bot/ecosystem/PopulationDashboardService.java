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

    private final LiveBotConfig live;
    private final RconService rcon;
    private final SpeciesTaxonomy taxonomy;

    private volatile Cached cache;

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
        String raw = rcon.run("playerlist");
        PopulationSnapshot snap = PlayerlistPopulationParser.parse(raw, taxonomy);
        if (PlayerlistPopulationParser.shouldFetchBulkGetplayerdata(raw, snap)) {
            try {
                String bulk = rcon.run("getplayerdata");
                snap = PlayerlistPopulationParser.parse(raw, bulk, taxonomy);
            } catch (IOException e) {
                LOG.warn("ecosystem: bulk getplayerdata failed: {}", e.toString());
            }
        }
        cache = new Cached(now, snap);
        return new SnapshotResult(snap, false, 0);
    }
}
