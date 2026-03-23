package com.isle.evrima.bot.ecosystem;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Loads species display names, diet buckets, and match keys from YAML (bundled or config path).
 */
public final class SpeciesTaxonomy {

    public record Entry(String display, SpeciesDiet diet, List<String> keys, String emoji) {}

    private final List<Entry> entries;
    /** (keyLower, entry) sorted by key length descending for greedy matching. */
    private final List<KeyRef> sortedKeys;

    private record KeyRef(String keyLower, Entry entry, int keyLength) {}

    private SpeciesTaxonomy(List<Entry> entries, List<KeyRef> sortedKeys) {
        this.entries = List.copyOf(entries);
        this.sortedKeys = List.copyOf(sortedKeys);
    }

    public static SpeciesTaxonomy loadBundled() throws IOException {
        try (InputStream in = SpeciesTaxonomy.class.getResourceAsStream("/species-taxonomy.yml")) {
            if (in == null) {
                throw new IOException("Missing classpath /species-taxonomy.yml");
            }
            return fromYamlMap(new Yaml().load(in));
        }
    }

    @SuppressWarnings("unchecked")
    public static SpeciesTaxonomy loadFile(Path yamlFile) throws IOException {
        try (InputStream in = Files.newInputStream(yamlFile)) {
            Object root = new Yaml().load(in);
            return fromYamlMap(root);
        }
    }

    @SuppressWarnings("unchecked")
    private static SpeciesTaxonomy fromYamlMap(Object root) throws IOException {
        if (!(root instanceof Map)) {
            throw new IOException("taxonomy root must be a map");
        }
        Map<String, Object> map = (Map<String, Object>) root;
        Object rawList = map.get("entries");
        if (!(rawList instanceof List)) {
            throw new IOException("taxonomy.entries must be a list");
        }
        List<Entry> out = new ArrayList<>();
        for (Object row : (List<?>) rawList) {
            if (!(row instanceof Map)) {
                continue;
            }
            Map<String, Object> rowMap = (Map<String, Object>) row;
            String display = string(rowMap.get("display"));
            if (display.isEmpty()) {
                continue;
            }
            SpeciesDiet diet = parseDiet(string(rowMap.get("diet")));
            List<String> keys = new ArrayList<>();
            keys.add(display);
            Object kl = rowMap.get("keys");
            if (kl instanceof List<?> klist) {
                for (Object k : klist) {
                    String s = string(k);
                    if (!s.isEmpty()) {
                        keys.add(s);
                    }
                }
            }
            addSyntheticBlueprintKeys(display, keys);
            String emoji = string(rowMap.get("emoji"));
            out.add(new Entry(display, diet, List.copyOf(keys), emoji));
        }
        List<KeyRef> refs = new ArrayList<>();
        for (Entry e : out) {
            for (String k : e.keys()) {
                String kl = k.toLowerCase(Locale.ROOT).trim();
                if (!kl.isEmpty()) {
                    refs.add(new KeyRef(kl, e, kl.length()));
                }
            }
        }
        refs.sort(Comparator.comparingInt(KeyRef::keyLength).reversed());
        return new SpeciesTaxonomy(out, refs);
    }

    private static String string(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    /** Typical Unreal / Evrima internal names next to Steam IDs in RCON dumps. */
    private static void addSyntheticBlueprintKeys(String display, List<String> keys) {
        String compact = display.replace(" ", "");
        if (compact.isEmpty()) {
            return;
        }
        addUniqueKey(keys, compact + "_C");
        addUniqueKey(keys, "BP_" + compact);
        addUniqueKey(keys, "BP_" + compact + "_C");
        addUniqueKey(keys, compact.toLowerCase(Locale.ROOT) + "_c");
    }

    private static void addUniqueKey(List<String> keys, String candidate) {
        if (candidate.isBlank()) {
            return;
        }
        for (String k : keys) {
            if (k.equalsIgnoreCase(candidate)) {
                return;
            }
        }
        keys.add(candidate);
    }

    private static SpeciesDiet parseDiet(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "carnivore", "carn" -> SpeciesDiet.CARNIVORE;
            case "herbivore", "herb" -> SpeciesDiet.HERBIVORE;
            case "omnivore", "omni" -> SpeciesDiet.OMNIVORE;
            default -> SpeciesDiet.UNKNOWN;
        };
    }

    /**
     * Best-effort: first longest key match wins (keys sorted by length).
     */
    public Entry matchLine(String line) {
        Objects.requireNonNull(line, "line");
        String hay = line.toLowerCase(Locale.ROOT);
        for (KeyRef ref : sortedKeys) {
            if (matches(hay, ref.keyLower())) {
                return ref.entry();
            }
        }
        return null;
    }

    /**
     * Like {@link #matchLine(String)} but also tries underscore/dot-normalized text and each alphanumeric token
     * (helps {@code BP_Tyrannosaurus_C} and comma-separated internal names).
     */
    public Entry matchLineOrTokens(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        Entry e = matchLine(line);
        if (e != null) {
            return e;
        }
        String norm = line.replace('_', ' ').replace('.', ' ');
        if (!norm.equals(line)) {
            e = matchLine(norm);
            if (e != null) {
                return e;
            }
        }
        for (String token : norm.split("[^A-Za-z0-9]+")) {
            if (token.length() < 4 || token.length() > 64) {
                continue;
            }
            if (token.chars().allMatch(Character::isDigit)) {
                continue;
            }
            e = matchLine(token);
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    private static boolean matches(String hayLower, String keyLower) {
        if (keyLower.length() >= 5) {
            return hayLower.contains(keyLower);
        }
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(keyLower) + "(?![a-z0-9])")
                .matcher(hayLower)
                .find();
    }

    public List<Entry> entries() {
        return entries;
    }

    /**
     * Emoji for embed display: YAML {@code emoji:} if set, otherwise a small diet-based default (🦖 / 🦕 / 🐦).
     */
    public String emojiForDisplay(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "";
        }
        String want = displayName.strip();
        for (Entry e : entries) {
            if (e.display().equalsIgnoreCase(want)) {
                if (e.emoji() != null && !e.emoji().isBlank()) {
                    return e.emoji().strip();
                }
                return dietEmoji(e.diet());
            }
        }
        return "";
    }

    private static String dietEmoji(SpeciesDiet d) {
        return switch (d) {
            case CARNIVORE -> "🦖";
            case HERBIVORE -> "🦕";
            case OMNIVORE -> "🐦";
            default -> "🦴";
        };
    }

    /**
     * Count how often each species appears in a blob (one TCP line, JSON, or pipe-separated list).
     * Scans left-to-right matching the longest taxonomy key at each step — better for single-line playerlists.
     */
    public Map<String, Integer> countSpeciesGreedy(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        String hay = raw.toLowerCase(Locale.ROOT);
        Map<String, Integer> counts = new LinkedHashMap<>();
        int pos = 0;
        while (pos < hay.length()) {
            KeyRef matched = null;
            for (KeyRef ref : sortedKeys) {
                if (matchesAt(hay, pos, ref.keyLower())) {
                    matched = ref;
                    break;
                }
            }
            if (matched != null) {
                counts.merge(matched.entry().display(), 1, Integer::sum);
                pos += matched.keyLength();
            } else {
                pos++;
            }
        }
        return counts;
    }

    private static boolean matchesAt(String hayLower, int pos, String keyLower) {
        if (pos + keyLower.length() > hayLower.length()) {
            return false;
        }
        if (!hayLower.startsWith(keyLower, pos)) {
            return false;
        }
        if (keyLower.length() >= 5) {
            return true;
        }
        char before = pos > 0 ? hayLower.charAt(pos - 1) : '\0';
        char after = pos + keyLower.length() < hayLower.length()
                ? hayLower.charAt(pos + keyLower.length())
                : '\0';
        return !Character.isLetterOrDigit(before) && !Character.isLetterOrDigit(after);
    }
}
