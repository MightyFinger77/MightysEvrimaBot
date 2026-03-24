package com.isle.evrima.bot.config;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * One population band (percent of {@code max_players} online) mapped to an RCON {@code aidensity} multiplier.
 * Percent bounds are inclusive on both ends ({@code min_percent}..{@code max_percent}).
 */
public record AdaptiveAiDensityTier(int minPercentInclusive, int maxPercentInclusive, double density) {

    /**
     * When YAML {@code tiers} is empty or omitted: same bands as the bundled default {@code config.yml}
     * ({@code adaptive_ai_density.tiers} template).
     */
    public static List<AdaptiveAiDensityTier> defaultTiers() {
        return List.of(
                new AdaptiveAiDensityTier(0, 24, 2.0),
                new AdaptiveAiDensityTier(25, 49, 1.0),
                new AdaptiveAiDensityTier(50, 79, 0.75),
                new AdaptiveAiDensityTier(80, 100, 0.5)
        );
    }

    /**
     * @param fillPercent 0–100 from {@code floor(100 * players / max_players)}, capped at 100
     */
    public static Optional<AdaptiveAiDensityTier> match(int fillPercent, List<AdaptiveAiDensityTier> tiersSorted) {
        int f = Math.max(0, Math.min(100, fillPercent));
        for (AdaptiveAiDensityTier t : tiersSorted) {
            if (f >= t.minPercentInclusive() && f <= t.maxPercentInclusive()) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    public static List<AdaptiveAiDensityTier> sortedCopy(List<AdaptiveAiDensityTier> tiers) {
        return tiers.stream().sorted(Comparator.comparingInt(AdaptiveAiDensityTier::minPercentInclusive)).toList();
    }
}
