package com.isle.evrima.bot.schedule;

import com.isle.evrima.bot.config.BotConfig;
import com.isle.evrima.bot.ecosystem.PopulationDashboardService;
import com.isle.evrima.bot.ecosystem.PopulationSnapshot;
import com.isle.evrima.bot.rcon.RconService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dynamically locks/unlocks configured species by editing Evrima playables with RCON {@code updateplayables}.
 * Lock when {@code count >= cap}; unlock when {@code count <= cap - unlock_below_offset}.
 */
public final class SpeciesPopulationControlScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(SpeciesPopulationControlScheduler.class);

    private final BotConfig config;
    private final RconService rcon;
    private final PopulationDashboardService population;
    private final Set<String> disabledByScheduler = new LinkedHashSet<>();
    private final AtomicBoolean runtimeEnabled;
    private final Map<String, String> baseNameByLower = new LinkedHashMap<>();
    private final Map<String, Integer> capOverridesByLower = new LinkedHashMap<>();

    public SpeciesPopulationControlScheduler(BotConfig config, RconService rcon, PopulationDashboardService population) {
        this.config = Objects.requireNonNull(config, "config");
        this.rcon = Objects.requireNonNull(rcon, "rcon");
        this.population = Objects.requireNonNull(population, "population");
        this.runtimeEnabled = new AtomicBoolean(config.speciesPopulationControlEnabled());
        for (String s : config.speciesPopulationCaps().keySet()) {
            baseNameByLower.put(s.toLowerCase(Locale.ROOT), s);
        }
    }

    public void start() {
        if (config.speciesPopulationCaps().isEmpty()) {
            LOG.warn("species_population_control: enabled but no caps configured — scheduler not started");
            return;
        }
        int sec = Math.max(10, Math.min(600, config.speciesPopulationControlIntervalSeconds()));
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "evrima-species-pop-control");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleAtFixedRate(this::runSafe, 45, sec, TimeUnit.SECONDS);
        LOG.info("Species population control scheduler ready: every {}s, {} capped species, unlock offset {}, runtime_enabled={}",
                sec, config.speciesPopulationCaps().size(), config.speciesPopulationControlUnlockBelowOffset(), runtimeEnabled.get());
    }

    public boolean hasConfiguredCaps() {
        return !config.speciesPopulationCaps().isEmpty();
    }

    public boolean isEnabled() {
        return runtimeEnabled.get();
    }

    public void setEnabled(boolean enabled) {
        runtimeEnabled.set(enabled);
    }

    /** Sets runtime cap override (0 = unmanaged/unlimited for that species). */
    public synchronized void setCapOverride(String species, int cap) {
        String normalized = normalizeSpecies(species);
        capOverridesByLower.put(normalized.toLowerCase(Locale.ROOT), Math.max(0, cap));
    }

    public synchronized void clearCapOverride(String species) {
        String normalized = normalizeSpecies(species);
        capOverridesByLower.remove(normalized.toLowerCase(Locale.ROOT));
    }

    public synchronized Map<String, Integer> listCapOverrides() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : capOverridesByLower.entrySet()) {
            String name = baseNameByLower.getOrDefault(e.getKey(), e.getKey());
            out.put(name, e.getValue());
        }
        return out;
    }

    /** Effective cap map (config base + overrides). */
    public synchronized Map<String, Integer> effectiveCaps() {
        Map<String, Integer> out = new LinkedHashMap<>(config.speciesPopulationCaps());
        for (Map.Entry<String, Integer> e : capOverridesByLower.entrySet()) {
            String name = baseNameByLower.getOrDefault(e.getKey(), e.getKey());
            out.put(name, e.getValue());
        }
        return out;
    }

    private String normalizeSpecies(String species) {
        String s = species == null ? "" : species.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Species cannot be empty.");
        }
        String lower = s.toLowerCase(Locale.ROOT);
        return baseNameByLower.getOrDefault(lower, s);
    }

    private void runSafe() {
        if (!runtimeEnabled.get()) {
            return;
        }
        try {
            runOnce();
        } catch (IOException e) {
            LOG.warn("species_population_control tick failed: {}", e.toString());
        } catch (Exception e) {
            LOG.warn("species_population_control tick failed: {}", e.toString());
        }
    }

    private void runOnce() throws IOException {
        PopulationSnapshot snap = population.snapshot(true).data();
        Map<String, Integer> counts = lowerCaseCounts(snap.speciesCounts());

        String rawPlayables = rcon.run("getplayables");
        List<String> currentPlayables = parsePlayables(rawPlayables);
        if (currentPlayables.isEmpty()) {
            LOG.warn("species_population_control: getplayables returned empty/unknown payload; skip tick");
            return;
        }

        List<String> desired = new ArrayList<>(currentPlayables);
        Set<String> nextDisabled = new LinkedHashSet<>(disabledByScheduler);
        List<String> changes = new ArrayList<>();

        int unlockOffset = config.speciesPopulationControlUnlockBelowOffset();
        Map<String, Integer> caps = effectiveCaps();
        for (Map.Entry<String, Integer> e : caps.entrySet()) {
            String species = e.getKey();
            int cap = e.getValue();
            String key = species.toLowerCase(Locale.ROOT);
            boolean present = containsIgnoreCase(desired, species);
            boolean disabledByUs = nextDisabled.contains(key);
            if (cap <= 0) {
                if (disabledByUs && !present) {
                    desired.add(species);
                    nextDisabled.remove(key);
                    changes.add("UNLOCK " + species + " (cap=0)");
                }
                continue;
            }
            int count = counts.getOrDefault(key, 0);
            int unlockAt = Math.max(0, cap - unlockOffset);

            if (count >= cap && present) {
                removeIgnoreCase(desired, species);
                nextDisabled.add(key);
                changes.add("LOCK " + species + " (" + count + "/" + cap + ")");
                continue;
            }
            if (disabledByUs && count <= unlockAt && !present) {
                desired.add(species);
                nextDisabled.remove(key);
                changes.add("UNLOCK " + species + " (" + count + "/" + cap + ", unlock<= " + unlockAt + ")");
            }
        }

        if (changes.isEmpty()) {
            return;
        }

        String cmd = "updateplayables " + String.join(",", desired);
        try {
            rcon.run(cmd);
        } catch (IOException first) {
            LOG.warn("species_population_control: updateplayables failed, retrying in 1s: {}", first.toString());
            sleepQuietly(1000L);
            rcon.run(cmd);
        }

        disabledByScheduler.clear();
        disabledByScheduler.addAll(nextDisabled);
        String summary = String.join(" | ", changes);
        LOG.info("species_population_control: {}", summary);
        if (config.speciesPopulationControlAnnounceChanges()) {
            String msg = "[Species control] " + summary;
            rcon.run("announce " + msg.replace('\n', ' '));
        }
    }

    private static Map<String, Integer> lowerCaseCounts(Map<String, Integer> speciesCounts) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : speciesCounts.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            out.merge(e.getKey().toLowerCase(Locale.ROOT), Math.max(0, e.getValue()), Integer::sum);
        }
        return out;
    }

    private static List<String> parsePlayables(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String cleaned = raw.replace('\r', '\n').replace('|', ',').replace(';', ',');
        String[] tokens = cleaned.split("[,\\n]");
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String t : tokens) {
            String s = t == null ? "" : t.trim();
            if (s.isEmpty()) {
                continue;
            }
            if ("getplayables".equalsIgnoreCase(s)) {
                continue;
            }
            if (s.length() > 64) {
                continue;
            }
            if (!hasLetter(s)) {
                continue;
            }
            String key = s.toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                out.add(s);
            }
        }
        return out;
    }

    private static boolean hasLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIgnoreCase(List<String> list, String target) {
        String t = target.toLowerCase(Locale.ROOT);
        for (String s : list) {
            if (s != null && s.toLowerCase(Locale.ROOT).equals(t)) {
                return true;
            }
        }
        return false;
    }

    private static void removeIgnoreCase(List<String> list, String target) {
        String t = target.toLowerCase(Locale.ROOT);
        list.removeIf(s -> s != null && s.toLowerCase(Locale.ROOT).equals(t));
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Convenience immutable view for status output. */
    public synchronized Map<String, Integer> effectiveCapsReadOnly() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(effectiveCaps()));
    }
}

