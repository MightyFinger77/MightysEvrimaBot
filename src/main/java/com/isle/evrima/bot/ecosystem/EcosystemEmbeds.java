package com.isle.evrima.bot.ecosystem;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class EcosystemEmbeds {

    /** Already a Discord custom emoji mention — pass through unchanged. */
    private static final Pattern CUSTOM_EMOTE =
            Pattern.compile("^<a?:[a-zA-Z0-9_]{2,32}:\\d{5,30}>$");

    private EcosystemEmbeds() {}

    /**
     * @param guild server where the embed is shown — used to resolve {@code :short_name:} in taxonomy into real emotes.
     *              May be null (e.g. DMs); short names are left as plain text.
     */
    public static MessageEmbed build(
            String title,
            PopulationSnapshot snap,
            SpeciesTaxonomy taxonomy,
            Guild guild) {
        int total = snap.referencePlayerTotal();

        StringBuilder overview = new StringBuilder();
        overview.append("🥩 **Carnivores:** ").append(snap.carnivores()).append("\n");
        overview.append("🌿 **Herbivores:** ").append(snap.herbivores()).append("\n");
        overview.append("🍽️ **Omnivores:** ").append(snap.omnivores()).append("\n");
        overview.append("**Dominant carnivore:** ")
                .append(dominantWithEmoji(taxonomy, guild, snap.dominantCarnivore()))
                .append("\n");
        overview.append("**Dominant herbivore:** ")
                .append(dominantWithEmoji(taxonomy, guild, snap.dominantHerbivore()))
                .append("\n");
        overview.append("**Dominant omnivore:** ")
                .append(dominantWithEmoji(taxonomy, guild, snap.dominantOmnivore()))
                .append("\n");
        overview.append("\n").append(balanceLine(snap));

        String speciesBlock = formatSpeciesTable(snap, total, taxonomy, guild);
        if (speciesBlock.length() > 3500) {
            speciesBlock = speciesBlock.substring(0, 3480) + "\n…(truncated)";
        }

        EmbedBuilder eb = new EmbedBuilder();
        eb.setTitle(title.isBlank() ? "Ecosystem tracker" : title);
        eb.setColor(new Color(0x57F287));
        StringBuilder pop = new StringBuilder();
        pop.append("**Players:** ").append(total).append("\n");
        if (snap.declaredPlayerCount() > 0) {
            pop.append("**Declared in RCON text:** ").append(snap.declaredPlayerCount());
        }
        eb.addField("Live server population", pop.toString(), false);
        eb.addField("Overview", overview.toString(), false);
        eb.addField("Species", speciesBlock.isBlank() ? "—" : speciesBlock, false);

        return eb.build();
    }

    /**
     * Discord does not expand {@code :name:} in API messages. If {@code raw} looks like {@code :isle:}, look up that
     * custom emoji on {@code guild} and return {@code <:isle:123...>}.
     */
    static String resolveEmojiForMessage(String raw, Guild guild) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.strip();
        if (CUSTOM_EMOTE.matcher(s).matches()) {
            return s;
        }
        if (guild == null || s.length() < 3 || s.charAt(0) != ':' || s.charAt(s.length() - 1) != ':') {
            return s;
        }
        String name = s.substring(1, s.length() - 1);
        if (!name.matches("[a-zA-Z0-9_]+")) {
            return s;
        }
        RichCustomEmoji hit = null;
        for (RichCustomEmoji e : guild.getEmojis()) {
            if (e.getName().equalsIgnoreCase(name)) {
                hit = e;
                break;
            }
        }
        return hit != null ? hit.getAsMention() : s;
    }

    private static String balanceLine(PopulationSnapshot snap) {
        int t = snap.referencePlayerTotal();
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

    /** Prepends species emoji to {@code Name (count)} dominant lines. */
    private static String dominantWithEmoji(SpeciesTaxonomy taxonomy, Guild guild, String line) {
        if (line == null || "None".equals(line)) {
            return line == null ? "None" : line;
        }
        int open = line.lastIndexOf(" (");
        if (open < 0) {
            return line;
        }
        String name = line.substring(0, open).strip();
        String tail = line.substring(open);
        String em = taxonomy.emojiForDisplay(name);
        if (em.isEmpty()) {
            return line;
        }
        return resolveEmojiForMessage(em, guild) + " " + name + tail;
    }

    private static String formatSpeciesTable(
            PopulationSnapshot snap, int total, SpeciesTaxonomy taxonomy, Guild guild) {
        if (snap.speciesCounts().isEmpty()) {
            return "";
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(snap.speciesCounts().entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed());
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : sorted) {
            int n = e.getValue();
            String pct = total > 0 ? String.format(Locale.ROOT, "%.1f%%", 100.0 * n / total) : "—";
            String em = taxonomy.emojiForDisplay(e.getKey());
            String lead = em.isEmpty() ? "" : resolveEmojiForMessage(em, guild) + " ";
            sb.append(lead)
                    .append("**")
                    .append(e.getKey())
                    .append(":** ")
                    .append(n)
                    .append(" (")
                    .append(pct)
                    .append(")\n");
        }
        if (snap.unknownSpeciesLines() > 0) {
            sb.append("❓ **Unknown / unmatched:** ").append(snap.unknownSpeciesLines());
            if (total > 0) {
                sb.append(" (")
                        .append(String.format(Locale.ROOT, "%.1f%%", 100.0 * snap.unknownSpeciesLines() / total))
                        .append(")");
            }
        }
        return sb.toString().strip();
    }
}
