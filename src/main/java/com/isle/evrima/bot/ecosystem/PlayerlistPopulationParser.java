package com.isle.evrima.bot.ecosystem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Heuristic parser: treats non-empty, non-boilerplate lines as one player row and classifies
 * species via {@link SpeciesTaxonomy}. Actual RCON text varies by game build — tune taxonomy keys
 * or extend this if your server uses a different layout.
 */
public final class PlayerlistPopulationParser {

    private static final Pattern HEADERISH = Pattern.compile(
            "^(#+|[-=*_]{3,}|online\\s*players?|player\\s*list|players?\\s*online|no\\s*players?|none|total\\s*[:\\s]*\\d+)\\.?$",
            Pattern.CASE_INSENSITIVE);

    private PlayerlistPopulationParser() {}

    public static PopulationSnapshot parse(String rawPlayerlist, SpeciesTaxonomy taxonomy) {
        String raw = rawPlayerlist == null ? "" : rawPlayerlist;
        List<String> lines = splitLines(raw);
        List<String> playerLines = new ArrayList<>();
        for (String line : lines) {
            if (isPlayerRow(line)) {
                playerLines.add(line);
            }
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        int carn = 0;
        int herb = 0;
        int omni = 0;
        int unknown = 0;

        for (String row : playerLines) {
            SpeciesTaxonomy.Entry hit = taxonomy.matchLine(row);
            if (hit == null) {
                unknown++;
                continue;
            }
            String d = hit.display();
            counts.merge(d, 1, Integer::sum);
            switch (hit.diet()) {
                case CARNIVORE -> carn++;
                case HERBIVORE -> herb++;
                case OMNIVORE -> omni++;
                default -> unknown++;
            }
        }

        return new PopulationSnapshot(
                playerLines.size(),
                counts,
                unknown,
                carn,
                herb,
                omni,
                dominant(counts, SpeciesDiet.CARNIVORE, taxonomy),
                dominant(counts, SpeciesDiet.HERBIVORE, taxonomy),
                dominant(counts, SpeciesDiet.OMNIVORE, taxonomy),
                raw
        );
    }

    private static String dominant(Map<String, Integer> counts, SpeciesDiet diet, SpeciesTaxonomy tax) {
        return counts.entrySet().stream()
                .filter(e -> dietOf(e.getKey(), tax) == diet)
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .map(e -> e.getKey() + " (" + e.getValue() + ")")
                .orElse("None");
    }

    private static SpeciesDiet dietOf(String display, SpeciesTaxonomy tax) {
        for (SpeciesTaxonomy.Entry e : tax.entries()) {
            if (e.display().equalsIgnoreCase(display)) {
                return e.diet();
            }
        }
        return SpeciesDiet.UNKNOWN;
    }

    private static List<String> splitLines(String raw) {
        String[] parts = raw.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String t = p.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static boolean isPlayerRow(String line) {
        if (line.length() < 2) {
            return false;
        }
        boolean hasLetter = false;
        for (int i = 0; i < line.length(); i++) {
            if (Character.isLetter(line.codePointAt(i))) {
                hasLetter = true;
                break;
            }
        }
        if (!hasLetter) {
            return false;
        }
        if (line.startsWith("#")) {
            return false;
        }
        if (HEADERISH.matcher(line).matches()) {
            return false;
        }
        return true;
    }

    /** Pretty single-line hint when parsing found no player rows (misconfigured list format). */
    public static String formatSpeciesLineSummary(PopulationSnapshot snap) {
        if (snap.playerLines() == 0) {
            return "No player rows detected — check raw RCON output shape vs `species-taxonomy.yml` keys.";
        }
        if (snap.unknownSpeciesLines() == snap.playerLines()) {
            return "Every line was **unknown** species — add keys to taxonomy or adjust the parser for your build.";
        }
        return "";
    }
}
