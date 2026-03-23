package com.isle.evrima.bot.ecosystem;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Loads species display names, diet buckets, and match keys from YAML (bundled or config path).
 */
public final class SpeciesTaxonomy {

    public record Entry(String display, SpeciesDiet diet, List<String> keys) {}

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
            out.add(new Entry(display, diet, List.copyOf(keys)));
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
}
