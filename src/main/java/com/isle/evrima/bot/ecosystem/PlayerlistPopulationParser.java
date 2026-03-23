package com.isle.evrima.bot.ecosystem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristic parser for RCON {@code playerlist} output. Many builds send one long line or use {@code |} / {@code ;}
 * instead of one player per newline — we expand segments, optionally scan the full text for species names, and use
 * SteamID64 / "N players" hints for totals.
 */
public final class PlayerlistPopulationParser {

    private static final Pattern HEADERISH = Pattern.compile(
            "^(#+|[-=*_]{3,}|online\\s*players?|player\\s*list|players?\\s*online|no\\s*players?|none|total\\s*[:\\s]*\\d+)\\.?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STEAM_ID64 = Pattern.compile("\\b7656119[0-9]{10}\\b");
    /** "119 players", "12 player online", etc. */
    private static final Pattern DECLARED_PLAYERS = Pattern.compile("(?i)(\\d{1,5})\\s+players?\\b");

    private PlayerlistPopulationParser() {}

    public static PopulationSnapshot parse(String rawPlayerlist, SpeciesTaxonomy taxonomy) {
        return parse(rawPlayerlist, null, taxonomy);
    }

    /**
     * @param rawBulkGetplayerdata optional RCON response from {@code getplayerdata} with no Steam ID (some hosts document
     *                             “all players”); merged when it improves species resolution vs {@code playerlist} alone.
     */
    public static PopulationSnapshot parse(String rawPlayerlist, String rawBulkGetplayerdata, SpeciesTaxonomy taxonomy) {
        String raw = rawPlayerlist == null ? "" : rawPlayerlist;
        int steam = countSteamId64(raw);
        int declared = extractDeclaredPlayerCount(raw);
        List<String> segments = expandToSegments(raw);
        List<String> playerLines = new ArrayList<>();
        for (String seg : segments) {
            if (isPlayerRow(seg)) {
                playerLines.add(seg);
            }
        }
        int nSeg = playerLines.size();

        Map<String, Integer> fromLines = new LinkedHashMap<>();
        int unknownFromLines = 0;
        int carnL = 0;
        int herbL = 0;
        int omniL = 0;
        for (String row : playerLines) {
            SpeciesTaxonomy.Entry hit = taxonomy.matchLine(row);
            if (hit == null) {
                unknownFromLines++;
                continue;
            }
            String d = hit.display();
            fromLines.merge(d, 1, Integer::sum);
            switch (hit.diet()) {
                case CARNIVORE -> carnL++;
                case HERBIVORE -> herbL++;
                case OMNIVORE -> omniL++;
                default -> unknownFromLines++;
            }
        }
        int sumLines = fromLines.values().stream().mapToInt(Integer::intValue).sum();

        Map<String, Integer> greedy = taxonomy.countSpeciesGreedy(raw);
        int sumGreedy = greedy.values().stream().mapToInt(Integer::intValue).sum();

        boolean useGreedy = shouldUseGreedy(nSeg, raw.length(), steam, declared, sumGreedy);

        SteamWindowsResult sw = steam >= 3 ? classifySteamWindows(raw, taxonomy) : null;
        boolean useSteamWindows = sw != null && sw.steamCount >= 3;

        Map<String, Integer> speciesCounts;
        int unknownPlayers;
        int carn;
        int herb;
        int omni;
        if (useSteamWindows) {
            speciesCounts = new LinkedHashMap<>(sw.counts);
            unknownPlayers = sw.unknown;
            carn = 0;
            herb = 0;
            omni = 0;
            for (Map.Entry<String, Integer> e : speciesCounts.entrySet()) {
                SpeciesDiet diet = dietOf(e.getKey(), taxonomy);
                int c = e.getValue();
                switch (diet) {
                    case CARNIVORE -> carn += c;
                    case HERBIVORE -> herb += c;
                    case OMNIVORE -> omni += c;
                    default -> { /* unknown diet */ }
                }
            }
        } else if (useGreedy) {
            speciesCounts = greedy;
            int refHint = Math.max(Math.max(Math.max(nSeg, steam), declared), sumGreedy);
            unknownPlayers = Math.max(0, refHint - sumGreedy);
            carn = 0;
            herb = 0;
            omni = 0;
            for (Map.Entry<String, Integer> e : speciesCounts.entrySet()) {
                SpeciesDiet diet = dietOf(e.getKey(), taxonomy);
                int c = e.getValue();
                switch (diet) {
                    case CARNIVORE -> carn += c;
                    case HERBIVORE -> herb += c;
                    case OMNIVORE -> omni += c;
                    default -> { /* unclassified species keys */ }
                }
            }
        } else {
            speciesCounts = fromLines;
            unknownPlayers = unknownFromLines;
            carn = carnL;
            herb = herbL;
            omni = omniL;
        }

        int sumSpecies = speciesCounts.values().stream().mapToInt(Integer::intValue).sum();
        int referencePlayerTotal = useSteamWindows
                ? Math.max(Math.max(steam, declared), sw.steamCount)
                : Math.max(Math.max(Math.max(nSeg, steam), declared), sumSpecies + unknownPlayers);

        String speciesDataNote = "";
        if (rawBulkGetplayerdata != null && !rawBulkGetplayerdata.isBlank()) {
            String gp = rawBulkGetplayerdata.strip();
            if (!gp.isEmpty()) {
                SteamWindowsResult gsw = classifySteamWindows(gp, taxonomy);
                int gswKnown = sumValues(gsw.counts);
                Map<String, Integer> gpGreedy = taxonomy.countSpeciesGreedy(gp);
                int greedyKnown = sumValues(gpGreedy);

                Map<String, Integer> chosen = null;
                int chosenUnknown = 0;
                int chosenSteamHint = steam;
                String note = null;

                if (gsw.steamCount >= 3 && gswKnown > 0) {
                    chosen = new LinkedHashMap<>(gsw.counts);
                    chosenUnknown = gsw.unknown;
                    chosenSteamHint = Math.max(steam, gsw.steamCount);
                    note = "Species breakdown from RCON **getplayerdata** (bulk, no Steam ID). Your **playerlist** had Steam IDs "
                            + "and display names only (no species field).";
                } else if (greedyKnown >= 3 && greedyKnown > gswKnown) {
                    chosen = new LinkedHashMap<>(gpGreedy);
                    chosenUnknown = Math.max(0, steam - greedyKnown);
                    note = "Species inferred from **getplayerdata** text (taxonomy pattern match). **playerlist** had no species rows.";
                }

                if (chosen != null && note != null) {
                    boolean idsNames = isSteamIdsPlusDisplayNamesOnly(raw);
                    int plKnown = sumValues(speciesCounts);
                    int chosenKnown = sumValues(chosen);
                    boolean replace = idsNames
                            || chosenKnown > plKnown
                            || (chosenUnknown < unknownPlayers && chosenKnown >= plKnown);
                    if (replace) {
                        speciesCounts = chosen;
                        unknownPlayers = chosenUnknown;
                        speciesDataNote = note;
                        carn = 0;
                        herb = 0;
                        omni = 0;
                        for (Map.Entry<String, Integer> e : speciesCounts.entrySet()) {
                            SpeciesDiet diet = dietOf(e.getKey(), taxonomy);
                            int c = e.getValue();
                            switch (diet) {
                                case CARNIVORE -> carn += c;
                                case HERBIVORE -> herb += c;
                                case OMNIVORE -> omni += c;
                                default -> { /* unknown diet */ }
                            }
                        }
                        sumSpecies = speciesCounts.values().stream().mapToInt(Integer::intValue).sum();
                        referencePlayerTotal = Math.max(
                                Math.max(Math.max(Math.max(nSeg, steam), declared), chosenSteamHint),
                                sumSpecies + unknownPlayers);
                    }
                }
            }
        }

        return new PopulationSnapshot(
                nSeg,
                referencePlayerTotal,
                steam,
                declared,
                speciesCounts,
                unknownPlayers,
                carn,
                herb,
                omni,
                dominant(speciesCounts, SpeciesDiet.CARNIVORE, taxonomy),
                dominant(speciesCounts, SpeciesDiet.HERBIVORE, taxonomy),
                dominant(speciesCounts, SpeciesDiet.OMNIVORE, taxonomy),
                speciesDataNote,
                raw
        );
    }

    /**
     * When true, ecosystem snapshot should issue a second RCON {@code getplayerdata} with no arguments and re-parse.
     */
    public static boolean shouldFetchBulkGetplayerdata(String playerlistRaw, PopulationSnapshot provisional) {
        if (playerlistRaw == null || playerlistRaw.isBlank()) {
            return false;
        }
        if (isSteamIdsPlusDisplayNamesOnly(playerlistRaw)) {
            return true;
        }
        int steam = provisional.steamId64Count();
        if (steam < 5) {
            return false;
        }
        return provisional.unknownSpeciesLines() * 2 > provisional.referencePlayerTotal();
    }

    private static int sumValues(Map<String, Integer> m) {
        int s = 0;
        for (int v : m.values()) {
            s += v;
        }
        return s;
    }

    /**
     * One slice per SteamID64: from this id up to (but not including) the next id — usual layout for single-line RCON dumps.
     */
    private static SteamWindowsResult classifySteamWindows(String raw, SpeciesTaxonomy taxonomy) {
        Matcher m = STEAM_ID64.matcher(raw);
        List<Integer> starts = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        int unknown = 0;
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i);
            int to = (i + 1 < starts.size()) ? starts.get(i + 1) : raw.length();
            int sliceFrom = Math.max(0, from - 768);
            if (to - sliceFrom > 8192) {
                to = sliceFrom + 8192;
            }
            String slice = raw.substring(sliceFrom, to);
            SpeciesTaxonomy.Entry hit = taxonomy.matchLineOrTokens(slice);
            if (hit == null) {
                unknown++;
            } else {
                counts.merge(hit.display(), 1, Integer::sum);
            }
        }
        return new SteamWindowsResult(counts, unknown, starts.size());
    }

    private record SteamWindowsResult(Map<String, Integer> counts, int unknown, int steamCount) {}

    private static boolean shouldUseGreedy(int nSeg, int rawLen, int steam, int declared, int sumGreedy) {
        if (sumGreedy <= 0) {
            return false;
        }
        if (nSeg <= 2 && rawLen > 200) {
            return true;
        }
        if (sumGreedy > nSeg + 1) {
            return true;
        }
        int ext = Math.max(steam, declared);
        return ext > nSeg && sumGreedy >= 3;
    }

    private static int countSteamId64(String raw) {
        Matcher m = STEAM_ID64.matcher(raw);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static int extractDeclaredPlayerCount(String raw) {
        Matcher m = DECLARED_PLAYERS.matcher(raw);
        int best = 0;
        while (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                if (v > best && v < 100_000) {
                    best = v;
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return best;
    }

    /**
     * Split newlines, then split long lines on {@code |} or {@code ;} (common in single-packet playerlists).
     */
    private static List<String> expandToSegments(String raw) {
        String norm = raw.replace("\r\n", "\n").replace('\r', '\n');
        List<String> out = new ArrayList<>();
        for (String line : norm.split("\n")) {
            String t = line.strip();
            if (t.isEmpty()) {
                continue;
            }
            if (t.contains("|")) {
                for (String part : t.split("\\|")) {
                    String p = part.strip();
                    if (!p.isEmpty()) {
                        out.add(p);
                    }
                }
            } else if (t.length() > 100 && t.contains(";")) {
                for (String part : t.split(";")) {
                    String p = part.strip();
                    if (!p.isEmpty()) {
                        out.add(p);
                    }
                }
            } else {
                out.add(t);
            }
        }
        return out;
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

    /**
     * Your build’s {@code playerlist} can be two comma-separated lines: SteamID64s, then display names — no species.
     */
    public static boolean isSteamIdsPlusDisplayNamesOnly(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        List<String> chunks = new ArrayList<>();
        for (String ln : raw.replace("\r\n", "\n").split("\n")) {
            String s = ln.strip();
            if (!s.isEmpty()) {
                chunks.add(s);
            }
        }
        if (chunks.size() < 2) {
            return false;
        }
        int idx = 0;
        if (chunks.get(0).equalsIgnoreCase("playerlist")) {
            idx++;
        }
        if (idx >= chunks.size()) {
            return false;
        }
        String idLine = chunks.get(idx);
        if (!commaSeparatedLineIsMostlySteamIds(idLine, 0.82)) {
            return false;
        }
        idx++;
        if (idx >= chunks.size()) {
            return false;
        }
        String nameLine = chunks.get(idx);
        if (commaSeparatedLineIsMostlySteamIds(nameLine, 0.4)) {
            return false;
        }
        if (nameLine.chars().noneMatch(Character::isLetter)) {
            return false;
        }
        int steamTokens = countSteamMatchesInText(idLine);
        int nameTokens = countCommaSeparatedTokens(nameLine);
        if (nameTokens < 3) {
            return false;
        }
        int slack = Math.max(5, steamTokens / 15);
        if (Math.abs(nameTokens - steamTokens) <= slack) {
            return true;
        }
        // Truncated paste / log: fewer names than IDs but same two-line shape
        return steamTokens >= 10 && nameTokens >= 8 && nameTokens < steamTokens;
    }

    private static int countCommaSeparatedTokens(String line) {
        int n = 0;
        for (String p : line.split(",")) {
            if (!p.strip().isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private static int countSteamMatchesInText(String line) {
        Matcher m = STEAM_ID64.matcher(line);
        int c = 0;
        while (m.find()) {
            c++;
        }
        return c;
    }

    /** True when most comma-separated tokens look like full SteamID64 strings. */
    private static boolean commaSeparatedLineIsMostlySteamIds(String line, double minRatio) {
        List<String> tokens = new ArrayList<>();
        for (String p : line.split(",")) {
            String t = p.strip();
            if (!t.isEmpty()) {
                tokens.add(t);
            }
        }
        if (tokens.size() < 3) {
            return false;
        }
        int hits = 0;
        for (String t : tokens) {
            if (STEAM_ID64.matcher(t).matches()) {
                hits++;
            }
        }
        return hits >= tokens.size() * minRatio;
    }

    /** Pretty hint when totals look untrustworthy. */
    public static String formatSpeciesLineSummary(PopulationSnapshot snap) {
        if (snap.referencePlayerTotal() == 0) {
            return "No population data — RCON list empty or unparsable.";
        }
        if (snap.speciesDataNote() != null && !snap.speciesDataNote().isBlank()) {
            return snap.speciesDataNote();
        }
        String raw = snap.rawPlayerlist();
        if (raw != null && isSteamIdsPlusDisplayNamesOnly(raw)) {
            return "Your RCON **playerlist** only contains **Steam IDs** and **player display names** (like your paste) — "
                    + "there is **no dinosaur / species field** in that output, so **species breakdown is impossible** from this command alone. "
                    + "Player count uses Steam IDs. PrimalCore-style species charts use **other hooks** (not this RCON text). "
                    + "Adding `taxonomy_path` cannot fix missing data.";
        }
        if (snap.steamId64Count() > 5 && snap.unknownSpeciesLines() * 2 > snap.referencePlayerTotal()) {
            return "Species names in RCON don’t match bundled keys (often **internal** names like `BP_*_C` or short codes). "
                    + "Run `/evrima-admin playerlist`, copy **one player’s** substring (the part after their SteamID), "
                    + "and add those strings under `keys:` in `species-taxonomy.yml` (or `ecosystem.taxonomy_path`). "
                    + "The bot now auto-adds `BP_DisplayName_C`-style keys, but your build may differ.";
        }
        if (snap.parsedSegmentCount() <= 1
                && snap.rawPlayerlist() != null
                && snap.rawPlayerlist().length() > 400
                && snap.steamId64Count() > snap.parsedSegmentCount() + 2) {
            return "RCON text looks like **one blob**; player total uses **SteamID64** count; species use **per-player slices** "
                    + "or taxonomy keys.";
        }
        if (snap.unknownSpeciesLines() == snap.referencePlayerTotal()) {
            return "No species matched taxonomy — add keys for strings in your `playerlist` output.";
        }
        return "";
    }
}
