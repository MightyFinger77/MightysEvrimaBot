package com.isle.evrima.bot.ecosystem;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parsed population from a raw playerlist string + taxonomy.
 */
public final class PopulationSnapshot {

    /** Row-like segments after splitting newlines / {@code |} / {@code ;}. */
    private final int parsedSegmentCount;
    /**
     * Best estimate of how many players to use for totals and % (max of segments, Steam IDs, declared count, species sum).
     */
    private final int referencePlayerTotal;
    /** Occurrences of SteamID64-shaped ids in the raw text (hint when list is one blob). */
    private final int steamId64Count;
    /** Parsed from phrases like "119 players" in the raw text, or 0. */
    private final int declaredPlayerCount;
    private final Map<String, Integer> speciesCounts;
    private final int unknownSpeciesLines;
    private final int carnivores;
    private final int herbivores;
    private final int omnivores;
    private final String dominantCarnivore;
    private final String dominantHerbivore;
    private final String dominantOmnivore;
    /** Non-empty when species counts were merged from bulk {@code getplayerdata} or similar. */
    private final String speciesDataNote;
    private final String rawPlayerlist;

    public PopulationSnapshot(
            int parsedSegmentCount,
            int referencePlayerTotal,
            int steamId64Count,
            int declaredPlayerCount,
            Map<String, Integer> speciesCounts,
            int unknownSpeciesLines,
            int carnivores,
            int herbivores,
            int omnivores,
            String dominantCarnivore,
            String dominantHerbivore,
            String dominantOmnivore,
            String speciesDataNote,
            String rawPlayerlist
    ) {
        this.parsedSegmentCount = parsedSegmentCount;
        this.referencePlayerTotal = referencePlayerTotal;
        this.steamId64Count = steamId64Count;
        this.declaredPlayerCount = declaredPlayerCount;
        this.speciesCounts = Collections.unmodifiableMap(new LinkedHashMap<>(speciesCounts));
        this.unknownSpeciesLines = unknownSpeciesLines;
        this.carnivores = carnivores;
        this.herbivores = herbivores;
        this.omnivores = omnivores;
        this.dominantCarnivore = dominantCarnivore;
        this.dominantHerbivore = dominantHerbivore;
        this.dominantOmnivore = dominantOmnivore;
        this.speciesDataNote = speciesDataNote == null ? "" : speciesDataNote;
        this.rawPlayerlist = rawPlayerlist;
    }

    /** @deprecated use {@link #parsedSegmentCount()} */
    @Deprecated
    public int playerLines() {
        return parsedSegmentCount;
    }

    public int parsedSegmentCount() {
        return parsedSegmentCount;
    }

    public int referencePlayerTotal() {
        return referencePlayerTotal;
    }

    public int steamId64Count() {
        return steamId64Count;
    }

    public int declaredPlayerCount() {
        return declaredPlayerCount;
    }

    public Map<String, Integer> speciesCounts() {
        return speciesCounts;
    }

    public int unknownSpeciesLines() {
        return unknownSpeciesLines;
    }

    public int carnivores() {
        return carnivores;
    }

    public int herbivores() {
        return herbivores;
    }

    public int omnivores() {
        return omnivores;
    }

    public String dominantCarnivore() {
        return dominantCarnivore;
    }

    public String dominantHerbivore() {
        return dominantHerbivore;
    }

    public String dominantOmnivore() {
        return dominantOmnivore;
    }

    public String speciesDataNote() {
        return speciesDataNote;
    }

    public String rawPlayerlist() {
        return rawPlayerlist;
    }
}
