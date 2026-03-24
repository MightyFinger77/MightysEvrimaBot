package com.isle.evrima.bot.ecosystem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
/**
 * Maps a staff-provided {@code player} string (SteamID64 or display name) to a SteamID64 using a fresh RCON
 * {@code playerlist} body.
 */
public final class PlayerTargetResolver {

    private PlayerTargetResolver() {}

    public sealed interface Result permits Resolved, NotFound, Ambiguous {
    }

    public record Resolved(String steamId64) implements Result {}

    public record NotFound(String reason) implements Result {}

    public record Ambiguous(String query, List<PlayerlistPopulationParser.PlayerlistNameEntry> matches) implements Result {}

    /**
     * @param query   trimmed or not — normalized inside
     * @param rawPlayerlist response body from RCON {@code playerlist} (same pass the user might see in Discord)
     */
    public static Result resolve(String query, String rawPlayerlist) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) {
            return new NotFound("Player field is empty.");
        }
        if (PlayerlistPopulationParser.isSteamId64(q)) {
            return new Resolved(q);
        }
        List<PlayerlistPopulationParser.PlayerlistNameEntry> entries =
                PlayerlistPopulationParser.listSteamIdDisplayPairs(rawPlayerlist);
        if (entries.isEmpty()) {
            return new NotFound(
                    "Could not read Steam/name pairs from **playerlist**. Use a **SteamID64**, or check that your "
                            + "server’s `playerlist` output lists IDs and names (run `/evrima-admin playerlist`).");
        }

        List<PlayerlistPopulationParser.PlayerlistNameEntry> exact = new ArrayList<>();
        for (PlayerlistPopulationParser.PlayerlistNameEntry e : entries) {
            if (e.displayName().equalsIgnoreCase(q)) {
                exact.add(e);
            }
        }
        if (exact.size() == 1) {
            return new Resolved(exact.get(0).steamId64());
        }
        if (exact.size() > 1) {
            return new Ambiguous(q, List.copyOf(exact));
        }

        String ql = q.toLowerCase(Locale.ROOT);
        List<PlayerlistPopulationParser.PlayerlistNameEntry> prefix = new ArrayList<>();
        for (PlayerlistPopulationParser.PlayerlistNameEntry e : entries) {
            if (e.displayName().toLowerCase(Locale.ROOT).startsWith(ql)) {
                prefix.add(e);
            }
        }
        if (prefix.size() == 1) {
            return new Resolved(prefix.get(0).steamId64());
        }
        if (prefix.size() > 1) {
            return new Ambiguous(q, List.copyOf(prefix));
        }

        List<PlayerlistPopulationParser.PlayerlistNameEntry> contains = new ArrayList<>();
        for (PlayerlistPopulationParser.PlayerlistNameEntry e : entries) {
            if (e.displayName().toLowerCase(Locale.ROOT).contains(ql)) {
                contains.add(e);
            }
        }
        if (contains.size() == 1) {
            return new Resolved(contains.get(0).steamId64());
        }
        if (contains.size() > 1) {
            return new Ambiguous(q, List.copyOf(contains));
        }

        return new NotFound(
                "No online player matched **" + escapeMdLite(q) + "**. Use **SteamID64** or an in-game name from "
                        + "`/evrima-admin playerlist` (names must match what the server prints).");
    }

    /** @throws IllegalStateException with an ephemeral-safe message when the target cannot be resolved */
    public static String resolveToSteamOrThrow(String query, String rawPlayerlist) {
        Result r = resolve(query, rawPlayerlist);
        if (r instanceof Resolved res) {
            return res.steamId64();
        }
        if (r instanceof NotFound n) {
            throw new IllegalStateException(n.reason());
        }
        throw new IllegalStateException(formatAmbiguous((Ambiguous) r));
    }

    private static String formatAmbiguous(Ambiguous a) {
        StringBuilder sb = new StringBuilder();
        sb.append("Several players match **").append(escapeMdLite(a.query())).append("** — pick one:\n");
        int lim = Math.min(a.matches().size(), 12);
        for (int i = 0; i < lim; i++) {
            PlayerlistPopulationParser.PlayerlistNameEntry e = a.matches().get(i);
            sb.append("- `").append(escapeMdLite(e.displayName())).append("` → `").append(e.steamId64()).append("`\n");
        }
        if (a.matches().size() > lim) {
            sb.append("- … and ").append(a.matches().size() - lim).append(" more — use SteamID64.\n");
        }
        return sb.toString().strip();
    }

    private static String escapeMdLite(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_").replace("`", "\\`");
    }
}
