package com.isle.evrima.bot.config;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * One population band mapped to an RCON {@code aidensity} multiplier. Bounds are inclusive on both ends.
 * In {@link AdaptiveAiDensityBandMode#PERCENT} mode they are 0–100 (fill % of {@code max_players}).
 * In {@link AdaptiveAiDensityBandMode#AMOUNT} mode they are raw online player counts.
 */
public record AdaptiveAiDensityTier(int minInclusive, int maxInclusive, double density) {

    /**
     * When YAML {@code tiers} is empty or omitted: same bands as the bundled default {@code config.yml}
     * ({@code adaptive_ai_density.tiers} template) for percent mode.
     */
    public static List<AdaptiveAiDensityTier> defaultTiersPercent() {
        return List.of(
                new AdaptiveAiDensityTier(0, 24, 2.0),
                new AdaptiveAiDensityTier(25, 49, 1.0),
                new AdaptiveAiDensityTier(50, 79, 0.75),
                new AdaptiveAiDensityTier(80, 100, 0.5)
        );
    }

    /**
     * Default player-count bands mirroring the percent breakpoints (0–24 players, 25–49, …).
     * Last band extends to {@link Integer#MAX_VALUE} so large pops still match.
     */
    public static List<AdaptiveAiDensityTier> defaultTiersAmount() {
        return List.of(
                new AdaptiveAiDensityTier(0, 24, 2.0),
                new AdaptiveAiDensityTier(25, 49, 1.0),
                new AdaptiveAiDensityTier(50, 79, 0.75),
                new AdaptiveAiDensityTier(80, Integer.MAX_VALUE, 0.5)
        );
    }

    public static List<AdaptiveAiDensityTier> defaultTiersForMode(AdaptiveAiDensityBandMode mode) {
        return mode == AdaptiveAiDensityBandMode.AMOUNT ? defaultTiersAmount() : defaultTiersPercent();
    }

    /** @deprecated use {@link #defaultTiersPercent()} */
    @Deprecated
    public static List<AdaptiveAiDensityTier> defaultTiers() {
        return defaultTiersPercent();
    }

    /**
     * @param fillPercent 0–100 from {@code floor(100 * players / max_players)}, capped at 100
     */
    public static Optional<AdaptiveAiDensityTier> matchPercent(int fillPercent, List<AdaptiveAiDensityTier> tiersSorted) {
        int f = Math.max(0, Math.min(100, fillPercent));
        return matchValue(f, tiersSorted);
    }

    /** @param players online count from population snapshot (≥ 0) */
    public static Optional<AdaptiveAiDensityTier> matchPlayers(int players, List<AdaptiveAiDensityTier> tiersSorted) {
        int p = Math.max(0, players);
        return matchValue(p, tiersSorted);
    }

    private static Optional<AdaptiveAiDensityTier> matchValue(int value, List<AdaptiveAiDensityTier> tiersSorted) {
        for (AdaptiveAiDensityTier t : tiersSorted) {
            if (value >= t.minInclusive() && value <= t.maxInclusive()) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    public static List<AdaptiveAiDensityTier> sortedCopy(List<AdaptiveAiDensityTier> tiers) {
        return tiers.stream().sorted(Comparator.comparingInt(AdaptiveAiDensityTier::minInclusive)).toList();
    }
}
