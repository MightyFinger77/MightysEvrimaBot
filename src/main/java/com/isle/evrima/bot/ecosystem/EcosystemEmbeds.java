package com.isle.evrima.bot.ecosystem;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.Color;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class EcosystemEmbeds {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT).withZone(ZoneId.systemDefault());

    private EcosystemEmbeds() {}

    public static MessageEmbed build(String title, PopulationSnapshot snap, boolean cached, long cacheAgeSeconds) {
        int total = snap.playerLines();
        String hint = PlayerlistPopulationParser.formatSpeciesLineSummary(snap);

        StringBuilder overview = new StringBuilder();
        overview.append("**Carnivores:** ").append(snap.carnivores()).append("\n");
        overview.append("**Herbivores:** ").append(snap.herbivores()).append("\n");
        overview.append("**Omnivores:** ").append(snap.omnivores()).append("\n");
        overview.append("**Dominant carnivore:** ").append(snap.dominantCarnivore()).append("\n");
        overview.append("**Dominant herbivore:** ").append(snap.dominantHerbivore()).append("\n");
        if (snap.omnivores() > 0) {
            overview.append("**Dominant omnivore:** ").append(snap.dominantOmnivore()).append("\n");
        }
        overview.append("\n").append(balanceLine(snap));

        String speciesBlock = formatSpeciesTable(snap, total);
        if (speciesBlock.length() > 3500) {
            speciesBlock = speciesBlock.substring(0, 3480) + "\n…(truncated)";
        }

        EmbedBuilder eb = new EmbedBuilder();
        eb.setTitle(title.isBlank() ? "Ecosystem tracker" : title);
        eb.setColor(new Color(0x57F287));
        eb.addField("Live server population", "**Total player rows:** " + total, false);
        eb.addField("Overview", overview.toString(), false);
        eb.addField("Species (parsed)", speciesBlock.isBlank() ? "—" : speciesBlock, false);
        if (!hint.isBlank()) {
            eb.addField("Parser note", hint, false);
        }

        String footer = "Population dashboard • Updated " + FMT.format(Instant.now());
        if (cached && cacheAgeSeconds >= 0) {
            footer += " • cache " + cacheAgeSeconds + "s old (RCON shared)";
        }
        eb.setFooter(footer);

        return eb.build();
    }

    private static String balanceLine(PopulationSnapshot snap) {
        int t = snap.playerLines();
        if (t == 0) {
            return "No population data.";
        }
        int c = snap.carnivores();
        int h = snap.herbivores();
        int o = snap.omnivores();
        if (h == 0 && c > 5) {
            return "Heavy carnivore skew — herbivores at **0**.";
        }
        if (c == 0 && h > 5) {
            return "Heavy herbivore skew — carnivores at **0**.";
        }
        if (Math.abs(c - h) <= Math.max(3, t / 10) && o <= t / 4) {
            return "Ecosystem mix looks **balanced** (heuristic).";
        }
        return "Mixed populations — tune rules in your own workflows if you need hard limits.";
    }

    private static String formatSpeciesTable(PopulationSnapshot snap, int total) {
        if (snap.speciesCounts().isEmpty()) {
            return "";
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(snap.speciesCounts().entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : sorted) {
            int n = e.getValue();
            String pct = total > 0 ? String.format(Locale.ROOT, "%.1f%%", 100.0 * n / total) : "—";
            sb.append("**").append(e.getKey()).append(":** ").append(n).append(" (").append(pct).append(")\n");
        }
        if (snap.unknownSpeciesLines() > 0) {
            sb.append("**Unknown / unmatched:** ").append(snap.unknownSpeciesLines());
            if (total > 0) {
                sb.append(" (")
                        .append(String.format(Locale.ROOT, "%.1f%%", 100.0 * snap.unknownSpeciesLines() / total))
                        .append(")");
            }
        }
        return sb.toString().strip();
    }
}
