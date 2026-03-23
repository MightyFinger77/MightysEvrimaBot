package com.isle.evrima.bot.ecosystem;

import com.isle.evrima.bot.config.BotConfig;
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

    private final RconService rcon;
    private final SpeciesTaxonomy taxonomy;
    private final BotConfig config;

    private volatile Cached cache;

    private record Cached(long fetchedAtEpochMs, PopulationSnapshot snapshot) {}

    public record SnapshotResult(PopulationSnapshot data, boolean fromCache, long cacheAgeSeconds) {}

    public PopulationDashboardService(BotConfig config, RconService rcon, SpeciesTaxonomy taxonomy) {
        this.config = Objects.requireNonNull(config, "config");
        this.rcon = Objects.requireNonNull(rcon, "rcon");
        this.taxonomy = Objects.requireNonNull(taxonomy, "taxonomy");
    }

    public SpeciesTaxonomy taxonomy() {
        return taxonomy;
    }

    public static SpeciesTaxonomy loadTaxonomy(Path configYamlPath, String taxonomyRelative) throws IOException {
        if (taxonomyRelative == null || taxonomyRelative.isBlank()) {
            return SpeciesTaxonomy.loadBundled();
        }
        Path parent = configYamlPath.getParent();
        if (parent == null) {
            parent = Path.of(".");
        }
        Path custom = parent.resolve(taxonomyRelative).normalize();
        if (Files.isRegularFile(custom)) {
            LOG.info("Using custom species taxonomy: {}", custom.toAbsolutePath());
            return SpeciesTaxonomy.loadFile(custom);
        }
        LOG.warn("ecosystem.taxonomy_path not found ({}), using bundled taxonomy", custom.toAbsolutePath());
        return SpeciesTaxonomy.loadBundled();
    }

    /**
     * @param forceRefresh bypass TTL and hit RCON
     */
    public SnapshotResult snapshot(boolean forceRefresh) throws IOException {
        long ttlMs = Math.max(5_000L, config.ecosystemCacheTtlSeconds() * 1000L);
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
