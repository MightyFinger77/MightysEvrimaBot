package com.isle.evrima.bot.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Optional funny / flavor lines for in-game kill mirrors. Loaded from {@code kill-flavor.yml} beside
 * {@code config.yml}. If disabled in config, missing file, or no matching quips, callers keep factual formatting.
 */
public final class KillFlavorPack {

    /**
     * For AI kills only: when {@code generic_ai_pvp_quips} is non-empty, this fraction of kills pick from that list
     * instead of {@code ai_pvp_quips} species/default — so generic lines still show up alongside tailored ones.
     */
    private static final double AI_USE_GENERIC_POOL_CHANCE = 0.30;

    public static final KillFlavorPack EMPTY = new KillFlavorPack(
            List.of(),
            Map.of(),
            Map.of(),
            List.of(),
            List.of(),
            List.of()
    );

    private final List<String> naturalQuips;
    /** Lowercase killer species → quips (player PvP). */
    private final Map<String, List<String>> pvpQuips;
    /** Lowercase AI killer species → quips; key {@code default} is AI fallback. */
    private final Map<String, List<String>> aiPvpQuips;
    private final List<String> sameSpeciesQuips;
    private final List<String> genericPvpQuips;
    private final List<String> genericAiPvpQuips;

    private KillFlavorPack(
            List<String> naturalQuips,
            Map<String, List<String>> pvpQuips,
            Map<String, List<String>> aiPvpQuips,
            List<String> sameSpeciesQuips,
            List<String> genericPvpQuips,
            List<String> genericAiPvpQuips
    ) {
        this.naturalQuips = List.copyOf(naturalQuips);
        this.pvpQuips = Map.copyOf(pvpQuips);
        this.aiPvpQuips = Map.copyOf(aiPvpQuips);
        this.sameSpeciesQuips = List.copyOf(sameSpeciesQuips);
        this.genericPvpQuips = List.copyOf(genericPvpQuips);
        this.genericAiPvpQuips = List.copyOf(genericAiPvpQuips);
    }

    public boolean hasAny() {
        return !naturalQuips.isEmpty()
                || !pvpQuips.isEmpty()
                || !aiPvpQuips.isEmpty()
                || !sameSpeciesQuips.isEmpty()
                || !genericPvpQuips.isEmpty()
                || !genericAiPvpQuips.isEmpty();
    }

    /**
     * @param killerRaw empty → AI killer ({@code LogTheIsleKillData} with {@code []} name)
     */
    public Optional<String> rollPvpLine(
            boolean aiKiller,
            String killerSpeciesKey,
            String victimSpeciesKey,
            Map<String, String> escapedPlaceholders
    ) {
        Objects.requireNonNull(escapedPlaceholders, "escapedPlaceholders");
        String k = killerSpeciesKey == null ? "" : killerSpeciesKey.toLowerCase(Locale.ROOT).trim();
        String v = victimSpeciesKey == null ? "" : victimSpeciesKey.toLowerCase(Locale.ROOT).trim();

        List<String> pool = null;
        if (aiKiller) {
            List<String> speciesSpecific = pickList(aiPvpQuips, k);
            List<String> aiDefault = pickList(aiPvpQuips, "default");
            List<String> primary = (speciesSpecific != null && !speciesSpecific.isEmpty())
                    ? speciesSpecific
                    : aiDefault;
            boolean genericAiOk = !genericAiPvpQuips.isEmpty();
            boolean pickGenericAi = genericAiOk
                    && ThreadLocalRandom.current().nextDouble() < AI_USE_GENERIC_POOL_CHANCE;
            if (pickGenericAi) {
                pool = genericAiPvpQuips;
            } else if (primary != null && !primary.isEmpty()) {
                pool = primary;
            } else if (genericAiOk) {
                pool = genericAiPvpQuips;
            } else {
                pool = genericPvpQuips;
            }
        } else if (!k.isEmpty() && k.equals(v) && !sameSpeciesQuips.isEmpty()) {
            pool = sameSpeciesQuips;
        } else {
            pool = pickList(pvpQuips, k);
            if (pool == null || pool.isEmpty()) {
                pool = genericPvpQuips;
            }
        }
        if (pool == null || pool.isEmpty()) {
            return Optional.empty();
        }
        String template = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        return Optional.of(applyPlaceholders(template, escapedPlaceholders));
    }

    public Optional<String> rollNaturalLine(Map<String, String> escapedPlaceholders) {
        if (naturalQuips.isEmpty()) {
            return Optional.empty();
        }
        String template = naturalQuips.get(ThreadLocalRandom.current().nextInt(naturalQuips.size()));
        return Optional.of(applyPlaceholders(template, escapedPlaceholders));
    }

    private static List<String> pickList(Map<String, List<String>> map, String lowerKey) {
        if (map == null || lowerKey == null) {
            return null;
        }
        return map.get(lowerKey);
    }

    static String applyPlaceholders(String template, Map<String, String> values) {
        String out = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            String key = "{" + e.getKey() + "}";
            String val = e.getValue() == null ? "" : e.getValue();
            out = out.replace(key, val);
        }
        return out;
    }

    public static KillFlavorPack loadFromPath(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.isRegularFile(path)) {
            return EMPTY;
        }
        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(path)) {
            Object root = yaml.load(in);
            if (!(root instanceof Map<?, ?> rawMap)) {
                return EMPTY;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) rawMap;
            List<String> natural = stringList(map.get("natural_quips"));
            Map<String, List<String>> pvp = speciesQuipsMap(map.get("pvp_quips"));
            Map<String, List<String>> ai = speciesQuipsMap(map.get("ai_pvp_quips"));
            List<String> same = stringList(map.get("same_species_quips"));
            List<String> gen = stringList(map.get("generic_pvp_quips"));
            List<String> genAi = stringList(map.get("generic_ai_pvp_quips"));
            return new KillFlavorPack(natural, pvp, ai, same, gen, genAi);
        }
    }

    private static List<String> stringList(Object v) {
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                String s = o == null ? "" : String.valueOf(o).trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
            return Collections.unmodifiableList(out);
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? List.of() : List.of(s);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> speciesQuipsMap(Object v) {
        if (!(v instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            String key = e.getKey() == null ? "" : String.valueOf(e.getKey()).trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) {
                continue;
            }
            List<String> lines = stringList(e.getValue());
            if (!lines.isEmpty()) {
                out.put(key, lines);
            }
        }
        return Collections.unmodifiableMap(out);
    }
}
