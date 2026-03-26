package com.isle.evrima.bot.discord;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort victim SteamID64 from {@code LogTheIsleKillData} lines (for dino-park purge on death).
 */
public final class KillLogVictimSteam {

    private static final Pattern LEADING_TIMESTAMP =
            Pattern.compile("^\\[\\d{4}\\.\\d{2}\\.\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}:\\d{3}\\]\\s*\\[\\s*\\d+\\s*\\]\\s*");
    private static final Pattern BRACKET_TIME =
            Pattern.compile("^\\[\\d{4}\\.\\d{2}\\.\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\]\\s*");

    /** PvP / AI-vs-player: victim steam after {@code Killed the following player:}. */
    private static final Pattern PVP_VICTIM_STEAM = Pattern.compile(
            "(?i)Killed the following player:\\s*[^,]+,\\s*\\[(7656119\\d{10})\\]");

    /** {@code LogTheIsleKillData: [when] Name [steam] tail} */
    private static final Pattern LOG_KILL = Pattern.compile(
            "(?i)LogTheIsleKillData:\\s*\\[[^\\]]+\\]\\s+(.+?)\\s+\\[(7656119\\d{10})\\]\\s*(.*)");

    private KillLogVictimSteam() {}

    /**
     * @param raw one log line (may include UE timestamp prefix)
     * @return victim SteamID64 when recognized
     */
    public static Optional<String> extractVictimSteamId64(String raw) {
        if (raw == null || raw.isBlank() || !raw.contains("LogTheIsleKillData")) {
            return Optional.empty();
        }
        String t = stripLogPrefixes(raw.strip());
        Matcher pvp = PVP_VICTIM_STEAM.matcher(t);
        if (pvp.find()) {
            return Optional.of(pvp.group(1));
        }
        Matcher lk = LOG_KILL.matcher(t);
        if (lk.matches()) {
            return Optional.of(lk.group(2).trim());
        }
        return Optional.empty();
    }

    private static String stripLogPrefixes(String t) {
        String s = t;
        for (int i = 0; i < 4; i++) {
            String next = LEADING_TIMESTAMP.matcher(s).replaceFirst("").trim();
            if (next.equals(s)) {
                break;
            }
            s = next;
        }
        while (true) {
            Matcher bt = BRACKET_TIME.matcher(s);
            if (!bt.find() || bt.start() != 0) {
                break;
            }
            s = s.substring(bt.end()).trim();
        }
        return s.replace("Verbose: ", "").trim();
    }
}
