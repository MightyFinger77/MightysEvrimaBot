package com.isle.evrima.bot.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Mutates on-disk {@code config.yml} for slash commands (Minecraft-style: the file is the source of truth).
 * <p>
 * Species and scheduled-wipecorpses edits use {@link ConfigYamlLineEdit} so the rest of the file (banner, comments,
 * ordering, flow style in other sections) is not rewritten by a full SnakeYAML dump.
 * <p>
 * <b>Project rule:</b> any slash command that changes a value represented in {@code config.yml} must persist
 * through this class (then {@link LiveBotConfig#reloadFromDisk()} — typically {@code BotListener.applyYamlMutation}).
 * Do not store duplicate “runtime config” in SQLite {@code bot_kv}.
 */
public final class ConfigYamlUpdater {

    private static final String BUNDLED_CONFIG = "config.yml";
    private static volatile Map<String, Object> cachedExampleRoot;

    private ConfigYamlUpdater() {}

    public static void setSpeciesPopulationControlEnabled(Path configYaml, boolean enabled) throws IOException {
        ConfigYamlLineEdit.setSpeciesPopulationControlEnabled(configYaml, enabled);
    }

    /**
     * Resolves a Discord-typed species name to the exact key used in the bundled default {@code config.yml}
     * {@code species_population_control.caps} block (PascalCase, etc.). Use this before slash-command replies so the
     * confirmed name matches the file.
     *
     * @throws IllegalArgumentException if blank or not one of the bundled roster species (case-insensitive match)
     * @throws IOException if the bundled template cannot be read
     */
    public static String requireCanonicalSpeciesCapKey(String speciesQuery) throws IOException {
        if (speciesQuery == null) {
            throw new IllegalArgumentException("species is empty.");
        }
        String s = speciesQuery.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("species is empty.");
        }
        Map<String, Object> caps = bundledSpeciesCaps();
        String key = findKeyCaseInsensitive(caps, s);
        if (key == null) {
            throw new IllegalArgumentException(
                    "Unknown species \"" + s + "\". It must be one of the names under species_population_control.caps "
                            + "in the bundled default config (match is case-insensitive). No change was written.");
        }
        return key;
    }

    /**
     * Writes a cap for a species already validated via {@link #requireCanonicalSpeciesCapKey(String)}.
     * New lines use the bundled spelling; existing lines keep their on-disk key casing (see {@link ConfigYamlLineEdit}).
     */
    public static void setSpeciesCapWithBundledKey(Path configYaml, String canonicalSpeciesKey, int cap) throws IOException {
        Objects.requireNonNull(canonicalSpeciesKey, "canonicalSpeciesKey");
        ConfigYamlLineEdit.setSpeciesCap(configYaml, canonicalSpeciesKey, cap, canonicalSpeciesKey);
    }

    /**
     * Validates against the bundled roster, then updates {@code species_population_control.caps}.
     */
    public static void setSpeciesCap(Path configYaml, String speciesQuery, int cap) throws IOException {
        setSpeciesCapWithBundledKey(configYaml, requireCanonicalSpeciesCapKey(speciesQuery), cap);
    }

    /**
     * Sets the species cap to the value from the bundled default template ({@code config.yml}), or {@code 0} if not listed there.
     */
    public static void clearSpeciesCapToExampleDefault(Path configYaml, String species) throws IOException {
        Objects.requireNonNull(species, "species");
        String s = species.trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException("species is empty.");
        }
        Map<String, Object> exCaps = bundledSpeciesCaps();
        String exKey = findKeyCaseInsensitive(exCaps, s);
        if (exKey == null) {
            throw new IllegalArgumentException(
                    "Unknown species \"" + s + "\". It must be one of the names under species_population_control.caps "
                            + "in the bundled default config. No change was written.");
        }
        Integer def = yamlInt(exCaps.get(exKey));
        int value = def != null ? Math.max(0, def) : 0;
        ConfigYamlLineEdit.setSpeciesCap(configYaml, exKey, value, exKey);
    }

    public static void setScheduledWipecorpsesEnabled(Path configYaml, String mode) throws IOException {
        String m = normalizeWipeMode(mode);
        ConfigYamlLineEdit.setScheduledWipecorpsesField(configYaml, "enabled", m);
    }

    public static void setScheduledWipecorpsesIntervalMinutes(Path configYaml, int minutes) throws IOException {
        ConfigYamlLineEdit.setScheduledWipecorpsesField(configYaml, "interval_minutes", minutes);
    }

    public static void setScheduledWipecorpsesWarnBeforeMinutes(Path configYaml, int minutes) throws IOException {
        ConfigYamlLineEdit.setScheduledWipecorpsesField(configYaml, "warn_before_minutes", minutes);
    }

    public static void setScheduledWipecorpsesAnnounceMessage(Path configYaml, String message) throws IOException {
        ConfigYamlLineEdit.setScheduledWipecorpsesField(configYaml, "announce_message", message == null ? "" : message);
    }

    public static void setScheduledWipecorpsesDynamicMaxPlayers(Path configYaml, int v) throws IOException {
        ConfigYamlLineEdit.setScheduledWipecorpsesField(configYaml, "dynamic_max_players", v);
    }

    public static void setScheduledWipecorpsesDynamicEnablePercent(Path configYaml, int v) throws IOException {
        ConfigYamlLineEdit.setScheduledWipecorpsesField(configYaml, "dynamic_enable_percent", v);
    }

    public static void setScheduledWipecorpsesDynamicDisableGraceSeconds(Path configYaml, int v) throws IOException {
        ConfigYamlLineEdit.setScheduledWipecorpsesField(configYaml, "dynamic_disable_grace_seconds", v);
    }

    public enum ScheduledWipeResetKey {
        ENABLED,
        INTERVAL_MINUTES,
        WARN_BEFORE_MINUTES,
        ANNOUNCE_MESSAGE,
        DYNAMIC_MAX_PLAYERS,
        DYNAMIC_ENABLE_PERCENT,
        DYNAMIC_DISABLE_GRACE_SECONDS
    }

    public static void resetScheduledWipecorpsesField(Path configYaml, ScheduledWipeResetKey key) throws IOException {
        Map<String, Object> ex = loadBundledExampleRoot();
        Map<String, Object> exWipe = mapOrEmpty(ex.get("scheduled_wipecorpses"));
        String yamlKey = switch (key) {
            case ENABLED -> "enabled";
            case INTERVAL_MINUTES -> "interval_minutes";
            case WARN_BEFORE_MINUTES -> "warn_before_minutes";
            case ANNOUNCE_MESSAGE -> "announce_message";
            case DYNAMIC_MAX_PLAYERS -> "dynamic_max_players";
            case DYNAMIC_ENABLE_PERCENT -> "dynamic_enable_percent";
            case DYNAMIC_DISABLE_GRACE_SECONDS -> "dynamic_disable_grace_seconds";
        };
        if (!exWipe.containsKey(yamlKey)) {
            throw new IOException("Bundled example missing scheduled_wipecorpses." + yamlKey);
        }
        ConfigYamlLineEdit.setScheduledWipecorpsesField(configYaml, yamlKey, exWipe.get(yamlKey));
    }

    public static void resetScheduledWipecorpsesAll(Path configYaml) throws IOException {
        Map<String, Object> ex = loadBundledExampleRoot();
        Map<String, Object> exWipe = mapOrEmpty(ex.get("scheduled_wipecorpses"));
        ConfigYamlLineEdit.mergeScheduledWipecorpsesFromExample(configYaml, exWipe);
    }

    private static String normalizeWipeMode(String mode) {
        if (mode == null) {
            return "false";
        }
        String m = mode.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(m) || "false".equals(m) || "dynamic".equals(m)) {
            return m;
        }
        throw new IllegalArgumentException("enabled mode must be true, false, or dynamic");
    }

    private static Integer yamlInt(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadBundledExampleRoot() throws IOException {
        Map<String, Object> c = cachedExampleRoot;
        if (c != null) {
            return c;
        }
        synchronized (ConfigYamlUpdater.class) {
            if (cachedExampleRoot != null) {
                return cachedExampleRoot;
            }
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            InputStream in = cl != null ? cl.getResourceAsStream(BUNDLED_CONFIG) : null;
            if (in == null) {
                in = ConfigYamlUpdater.class.getClassLoader().getResourceAsStream(BUNDLED_CONFIG);
            }
            if (in == null) {
                throw new IOException("Bundled " + BUNDLED_CONFIG + " not on classpath");
            }
            try (InputStream stream = in) {
                Object o = new Yaml().load(stream);
                if (!(o instanceof Map)) {
                    throw new IOException("bundled example root must be a map");
                }
                cachedExampleRoot = (Map<String, Object>) o;
            }
            return cachedExampleRoot;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOrEmpty(Object v) {
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private static Map<String, Object> bundledSpeciesCaps() throws IOException {
        Map<String, Object> ex = loadBundledExampleRoot();
        Map<String, Object> exSp = mapOrEmpty(ex.get("species_population_control"));
        return mapOrEmpty(exSp.get("caps"));
    }

    private static String findKeyCaseInsensitive(Map<String, Object> caps, String species) {
        String want = species.toLowerCase(Locale.ROOT);
        for (String k : caps.keySet()) {
            if (k != null && k.toLowerCase(Locale.ROOT).equals(want)) {
                return k;
            }
        }
        return null;
    }
}
