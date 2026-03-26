package com.isle.evrima.bot.config;

/**
 * How {@code adaptive_ai_density.tiers} bounds are interpreted: fill % of {@code max_players}, or raw online count.
 */
public enum AdaptiveAiDensityBandMode {
    PERCENT,
    AMOUNT;

    public static AdaptiveAiDensityBandMode parseYaml(Object v) {
        if (v == null) {
            return PERCENT;
        }
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty()) {
            return PERCENT;
        }
        return switch (s) {
            case "percent", "percentage", "pct", "%" -> PERCENT;
            case "amount", "players", "count", "absolute" -> AMOUNT;
            default -> PERCENT;
        };
    }
}
