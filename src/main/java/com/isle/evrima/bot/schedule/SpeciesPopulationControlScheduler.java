package com.isle.evrima.bot.schedule;

import com.isle.evrima.bot.config.BotConfig;
import com.isle.evrima.bot.config.LiveBotConfig;
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

/**
 * Dynamically locks/unlocks configured species by editing Evrima playables with RCON {@code updateplayables}.
 * Lock when {@code count >= cap}; unlock when {@code count <= cap - unlock_below_offset} and the species is
 * absent from the RCON playables list. Unlock does <b>not</b> require the bot to have locked the species earlier
 * in the same process (so re-enabling works after restarts or if a prior lock was missed).
 * All limits and enabled flag come from {@code config.yml} (reloaded when admins change them via Discord).
 */
public final class SpeciesPopulationControlScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(SpeciesPopulationControlScheduler.class);

    private final LiveBotConfig live;
    private final RconService rcon;
    private final PopulationDashboardService population;
    private final Set<String> disabledByScheduler = new LinkedHashSet<>();
    private volatile BotConfig configCache;
    private final Object nameLock = new Object();
    private final Map<String, String> baseNameByLower = new LinkedHashMap<>();

    public SpeciesPopulationControlScheduler(LiveBotConfig live, RconService rcon, PopulationDashboardService population) {
        this.live = Objects.requireNonNull(live, "live");
        this.rcon = Objects.requireNonNull(rcon, "rcon");
        this.population = Objects.requireNonNull(population, "population");
    }

    private BotConfig cfg() {
        BotConfig c = live.get();
        if (c != configCache) {
            synchronized (nameLock) {
                if (c != configCache) {
                    baseNameByLower.clear();
                    for (String s : c.speciesPopulationCaps().keySet()) {
                        baseNameByLower.put(s.toLowerCase(Locale.ROOT), s);
                    }
                    configCache = c;
                }
            }
        }
        return c;
    }

    /**
     * Call after {@link LiveBotConfig#reloadFromDisk()} so the case-insensitive species name map matches the new caps.
     */
    public void invalidateConfigCache() {
        configCache = null;
    }

    public void start() {
        BotConfig c = cfg();
        int sec = Math.max(10, Math.min(600, c.speciesPopulationControlIntervalSeconds()));
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "evrima-species-pop-control");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleAtFixedRate(this::runSafe, 45, sec, TimeUnit.SECONDS);
        LOG.info("Species population control scheduler ready: every {}s, {} capped species, unlock offset {}, enabled={}",
                sec, c.speciesPopulationCaps().size(), c.speciesPopulationControlUnlockBelowOffset(),
                c.speciesPopulationControlEnabled());
    }

    public boolean hasConfiguredCaps() {
        return !cfg().speciesPopulationCaps().isEmpty();
    }

    public boolean isEnabled() {
        return cfg().speciesPopulationControlEnabled();
    }

    public Map<String, Integer> effectiveCaps() {
        return new LinkedHashMap<>(cfg().speciesPopulationCaps());
    }

    private void runSafe() {
        if (!cfg().speciesPopulationControlEnabled()) {
            return;
        }
        if (cfg().speciesPopulationCaps().isEmpty()) {
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

        BotConfig c = cfg();
        int unlockOffset = c.speciesPopulationControlUnlockBelowOffset();
        Map<String, Integer> caps = effectiveCaps();
        for (Map.Entry<String, Integer> e : caps.entrySet()) {
            String species = e.getKey();
            int cap = e.getValue();
            String key = species.toLowerCase(Locale.ROOT);
            boolean present = containsIgnoreCase(desired, species);
            if (cap <= 0) {
                if (nextDisabled.contains(key) && !present) {
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
            // Re-add when under the unlock threshold and missing from playables — even if we did not record a
            // LOCK this session (restart, or LOCK never ran because the species was already absent at peak pop).
            if (count <= unlockAt && !present) {
                if (!containsIgnoreCase(desired, species)) {
                    desired.add(species);
                }
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
        LOG.info("species_population_control (scheduler, automatic): playable species updated — RCON updateplayables — {}",
                summary);
        if (c.speciesPopulationControlAnnounceChanges()) {
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

    /**
     * Merges the live RCON {@code getplayables} list with every species name under {@code species_population_control.caps}
     * (so entries this scheduler removed are playable again), then sends {@code updateplayables}. Call immediately before
     * turning {@code species_population_control.enabled} off via config.
     * <p>
     * If there are no cap keys configured, clears in-memory lock tracking only. If {@code getplayables} parses to an empty
     * list but caps exist, throws — applying only cap names would drop other server playables.
     *
     * @return {@code true} if {@code updateplayables} was sent
     */
    public boolean restorePlayablesBeforeDisablingSpeciesControl() throws IOException {
        BotConfig c = cfg();
        Map<String, Integer> caps = c.speciesPopulationCaps();
        if (caps.isEmpty()) {
            disabledByScheduler.clear();
            LOG.info("species_population_control: disable — no caps in config, skipping playables merge");
            return false;
        }
        String rawPlayables = rcon.run("getplayables");
        List<String> desired = new ArrayList<>(parsePlayables(rawPlayables));
        if (desired.isEmpty()) {
            throw new IOException(
                    "getplayables returned no species list — cannot safely restore (would risk wiping playables). "
                            + "Check RCON, then try again or fix playables manually.");
        }
        for (String species : caps.keySet()) {
            if (species == null) {
                continue;
            }
            String sp = species.trim();
            if (sp.isEmpty()) {
                continue;
            }
            if (!containsIgnoreCase(desired, sp)) {
                desired.add(sp);
            }
        }
        String cmd = "updateplayables " + String.join(",", desired);
        try {
            rcon.run(cmd);
        } catch (IOException first) {
            LOG.warn("species_population_control: restore updateplayables failed, retrying in 1s: {}", first.toString());
            sleepQuietly(1000L);
            rcon.run(cmd);
        }
        disabledByScheduler.clear();
        LOG.info("species_population_control: merged caps roster into playables before disable ({} species in list)",
                desired.size());
        return true;
    }

    public Map<String, Integer> effectiveCapsReadOnly() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(effectiveCaps()));
    }
}
