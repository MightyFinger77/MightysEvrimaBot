package com.isle.evrima.bot.ecosystem;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parsed population from a raw playerlist string + taxonomy.
 */
public final class PopulationSnapshot {

    private final int playerLines;
    private final Map<String, Integer> speciesCounts;
    private final int unknownSpeciesLines;
    private final int carnivores;
    private final int herbivores;
    private final int omnivores;
    private final String dominantCarnivore;
    private final String dominantHerbivore;
    private final String dominantOmnivore;
    private final String rawPlayerlist;

    public PopulationSnapshot(
            int playerLines,
            Map<String, Integer> speciesCounts,
            int unknownSpeciesLines,
            int carnivores,
            int herbivores,
            int omnivores,
            String dominantCarnivore,
            String dominantHerbivore,
            String dominantOmnivore,
            String rawPlayerlist
    ) {
        this.playerLines = playerLines;
        this.speciesCounts = Collections.unmodifiableMap(new LinkedHashMap<>(speciesCounts));
        this.unknownSpeciesLines = unknownSpeciesLines;
        this.carnivores = carnivores;
        this.herbivores = herbivores;
        this.omnivores = omnivores;
        this.dominantCarnivore = dominantCarnivore;
        this.dominantHerbivore = dominantHerbivore;
        this.dominantOmnivore = dominantOmnivore;
        this.rawPlayerlist = rawPlayerlist;
    }

    public int playerLines() {
        return playerLines;
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

    public String rawPlayerlist() {
        return rawPlayerlist;
    }
}
