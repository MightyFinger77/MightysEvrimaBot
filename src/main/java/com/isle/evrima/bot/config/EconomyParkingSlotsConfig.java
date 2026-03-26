package com.isle.evrima.bot.config;

/**
 * Optional economy limits for {@code /evrima dino park} capacity ({@code economy.parking_slots} in {@code config.yml}).
 */
public final class EconomyParkingSlotsConfig {

    /** Legacy behavior: no cap on parked rows. */
    public static final EconomyParkingSlotsConfig DISABLED = new EconomyParkingSlotsConfig(false, 1, 10, 100, 1.15);

    private final boolean enabled;
    private final int defaultSlots;
    private final int maxSlots;
    private final int basePricePerSlot;
    private final double priceMultiplier;

    public EconomyParkingSlotsConfig(
            boolean enabled, int defaultSlots, int maxSlots, int basePricePerSlot, double priceMultiplier) {
        this.enabled = enabled;
        this.defaultSlots = Math.max(0, defaultSlots);
        this.maxSlots = Math.max(this.defaultSlots, maxSlots);
        this.basePricePerSlot = Math.max(0, basePricePerSlot);
        double m = priceMultiplier;
        if (Double.isNaN(m) || m < 1.0d) {
            m = 1.0d;
        }
        if (m > 100.0d) {
            m = 100.0d;
        }
        this.priceMultiplier = m;
    }

    public boolean enabled() {
        return enabled;
    }

    public int defaultSlots() {
        return defaultSlots;
    }

    public int maxSlots() {
        return maxSlots;
    }

    public int basePricePerSlot() {
        return basePricePerSlot;
    }

    public double priceMultiplier() {
        return priceMultiplier;
    }

    /**
     * Max {@code parked_dinos} rows for this Discord user. When {@link #enabled()} is false, returns {@link Integer#MAX_VALUE}.
     */
    public int capacityForPurchasedExtras(int extraPurchased) {
        if (!enabled) {
            return Integer.MAX_VALUE;
        }
        int ex = Math.max(0, extraPurchased);
        return Math.min(maxSlots, defaultSlots + ex);
    }

    /**
     * @param extraPurchased how many slots were bought with points (not counting {@link #defaultSlots()})
     */
    public int priceForNextExtraSlot(int extraPurchased) {
        int ex = Math.max(0, extraPurchased);
        double raw = basePricePerSlot * Math.pow(priceMultiplier, ex);
        if (Double.isNaN(raw) || raw < 0) {
            return 0;
        }
        long rounded = Math.round(raw);
        if (rounded > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) rounded;
    }
}
