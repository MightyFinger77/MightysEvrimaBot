package com.isle.evrima.bot.discord;

import com.isle.evrima.bot.config.BotConfig;
import com.isle.evrima.bot.config.KillFlavorPack;
import com.isle.evrima.bot.config.LiveBotConfig;
import com.isle.evrima.bot.db.Database;
import com.isle.evrima.bot.dino.DinoParkLogoutAutosave;
import com.isle.evrima.bot.rcon.RconService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tails the dedicated server log file and posts matching lines (chat, kill/death, etc.) to a Discord channel.
 * {@code [Spatial]} / {@code [Local]} lines are optional via config ({@code mirror_local_chat}).
 * RCON does not stream chat; this follows the same idea as
 * <a href="https://github.com/Theislemanager/Chatbot">Theislemanager/Chatbot</a> (log file + substring match).
 */
public final class IngameChatLogScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(IngameChatLogScheduler.class);
    private static final String KV_PATH = "ingame_chat_log_abs_path";
    private static final String KV_OFFSET = "ingame_chat_log_offset";
    private static final int MAX_READ_CHUNK = 4_000_000;
    private static final int MAX_DISCORD = 1_900;

    /**
     * UE / The Isle style prefix: {@code [YYYY.MM.DD-HH.MM.SS:mmm][thread]}.
     * Thread id is often padded with spaces, e.g. {@code [ 76]} — {@code \[\d+\]} would miss and leave the whole prefix on the line.
     */
    private static final Pattern LEADING_TIMESTAMP =
            Pattern.compile("^\\[\\d{4}\\.\\d{2}\\.\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}:\\d{3}\\]\\s*\\[\\s*\\d+\\s*\\]\\s*");
    /** In-log bracket time e.g. {@code [2026.03.23-17.26.27]} before {@code LogTheIsle…} */
    private static final Pattern BRACKET_TIME =
            Pattern.compile("^\\[\\d{4}\\.\\d{2}\\.\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\]\\s*");

    /**
     * {@code LogTheIsleChatData: [when] [Global] [GROUP-…] Name [steam]: message}
     * Steam id and spaces before {@code :} are optional (some builds omit id or log {@code Name : text}).
     */
    private static final Pattern LOG_CHAT = Pattern.compile(
            "(?i)LogTheIsleChatData:\\s*\\[[^\\]]+\\]\\s+\\[([^\\]]+)\\]\\s+(?:\\[GROUP-[^\\]]+\\]\\s+)?(.+?)(?:\\s+\\[(7656119\\d{10})\\])?\\s*:\\s*(.*)");

    /** {@code LogTheIsleJoinData: [when] Name [steam] …} — join/leave heuristics on tail text */
    private static final Pattern LOG_JOIN_DATA = Pattern.compile(
            "(?i)LogTheIsleJoinData:\\s*\\[[^\\]]+\\]\\s+(.+?)\\s+\\[(7656119\\d{10})\\]\\s*(.*)", Pattern.DOTALL);

    /** Dedicated leave tag if your build logs it separately from {@link #LOG_JOIN_DATA}. */
    private static final Pattern LOG_LEAVE_DATA = Pattern.compile(
            "(?i)LogTheIsleLeaveData:\\s*\\[[^\\]]+\\]\\s+(.+?)\\s+\\[(7656119\\d{10})\\]\\s*(.*)", Pattern.DOTALL);

    private static final Pattern STEAM_64 = Pattern.compile("(7656119\\d{10})");

    /** Leave / disconnect phrasing inside JoinData lines on some Evrima builds. */
    private static final Pattern LEAVE_HINT = Pattern.compile(
            "(?i)left the server|disconnect|disconnected|safelog|logging out|logged out");

    /** {@code LogTheIsleKillData: [when] Name [steam] …} */
    private static final Pattern LOG_KILL = Pattern.compile(
            "(?i)LogTheIsleKillData:\\s*\\[[^\\]]+\\]\\s+(.+?)\\s+\\[(7656119\\d{10})\\]\\s*(.*)", Pattern.DOTALL);

    /**
     * AI / no-Steam killer: {@code LogTheIsleKillData: [when] [] Dino: Deer, … - Killed the following player: …}
     * (Matched before {@link #LOG_KILL} so lines with killer {@code []} are not parsed using the victim’s Steam id.)
     */
    private static final Pattern LOG_KILL_AI = Pattern.compile(
            "(?i)LogTheIsleKillData:\\s*\\[[^\\]]+\\]\\s+\\[\\s*\\]\\s+(.*)", Pattern.DOTALL);

    /**
     * Killer dino, then PvP narrative. Trailing growth/coords after victim {@code Gender:} are ignored.
     * {@code Gender:} is how victim dino is logged on some builds (vs {@code Female}/{@code Male} in killer slot).
     */
    private static final Pattern KILL_PVP = Pattern.compile(
            "(?is)^Dino:\\s*([^,]+),\\s*([^,]+),\\s*\\S+\\s*-\\s*Killed the following player:\\s*([^,]+),\\s*\\[7656119\\d{10}\\],\\s*Dino:\\s*([^,]+),\\s*Gender:\\s*([^,]+).*");

    /** Natural / environmental: {@code Dino: Species, Sex, growth - Died from …} */
    private static final Pattern KILL_NATURAL = Pattern.compile(
            "(?is)^Dino:\\s*([^,]+),\\s*([^,]+),\\s*\\S+\\s*-\\s*(.+)$");

    /** Killer stats without {@code Dino:} prefix: {@code Species, Sex, growth - Killed the following player: …} */
    private static final Pattern KILL_PVP_NO_PREFIX = Pattern.compile(
            "(?is)^([^,]+),\\s*([^,]+),\\s*\\S+\\s*-\\s*Killed the following player:\\s*([^,]+),\\s*\\[7656119\\d{10}\\],\\s*Dino:\\s*([^,]+),\\s*Gender:\\s*([^,]+).*");

    private final LiveBotConfig live;
    private final Database database;
    private final RconService rcon;
    private final StringBuilder incompleteLine = new StringBuilder();

    public IngameChatLogScheduler(LiveBotConfig live, Database database, RconService rcon) {
        this.live = Objects.requireNonNull(live, "live");
        this.database = Objects.requireNonNull(database, "database");
        this.rcon = Objects.requireNonNull(rcon, "rcon");
    }

    public void start(JDA jda) {
        long chId = live.get().ingameChatLogChannelId();
        if (chId == 0L) {
            return;
        }
        Path path = live.get().ingameChatLogPath();
        if (path == null) {
            LOG.warn("ingame_chat_log.channel_id set but path is blank — chat mirror disabled");
            return;
        }
        int sec = Math.max(1, Math.min(60, live.get().ingameChatLogPollSeconds()));
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "evrima-ingame-chat-log");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleAtFixedRate(() -> runSafe(jda, chId, path), 5, sec, TimeUnit.SECONDS);
        LOG.info("In-game log mirror: {} → Discord channel {} (every {}s, any of {})",
                path.toAbsolutePath(), chId, sec, live.get().ingameChatLogLineContainsAny());
    }

    private void runSafe(JDA jda, long channelId, Path logPath) {
        try {
            runOnce(jda, channelId, logPath);
        } catch (Exception e) {
            LOG.warn("ingame_chat_log tick failed: {}", e.toString());
        }
    }

    private void runOnce(JDA jda, long channelId, Path logPath) throws SQLException, IOException {
        TextChannel ch = jda.getTextChannelById(channelId);
        if (ch == null) {
            LOG.warn("ingame_chat_log.channel_id {} — channel not visible to bot", channelId);
            return;
        }
        if (!ch.canTalk()) {
            LOG.warn("ingame_chat_log.channel_id {} — missing send permission", channelId);
            return;
        }
        Path abs = logPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(abs)) {
            return;
        }

        String absStr = abs.toString();
        long offset = initOrRepairOffset(absStr, Files.size(abs));

        long size = Files.size(abs);
        if (size < offset) {
            offset = 0;
            incompleteLine.setLength(0);
            database.putBotKv(KV_OFFSET, "0");
        }
        if (size <= offset) {
            return;
        }

        long toRead = Math.min(size - offset, MAX_READ_CHUNK);
        byte[] buf = new byte[(int) toRead];
        try (RandomAccessFile raf = new RandomAccessFile(abs.toFile(), "r")) {
            raf.seek(offset);
            raf.readFully(buf);
        }
        offset += toRead;
        String chunk = new String(buf, StandardCharsets.UTF_8);
        incompleteLine.append(chunk);

        BotConfig cfg = live.get();
        List<String> toSend = extractMatchingLines(cfg.ingameChatLogLineContainsAny(), cfg.dinoParkPurgeOnKillLog(), cfg);

        database.putBotKv(KV_PATH, absStr);
        database.putBotKv(KV_OFFSET, String.valueOf(offset));

        for (String line : toSend) {
            String formatted = formatForDiscord(line.strip(), live.get().ingameChatLogMirrorLocalChat(), live.get());
            if (formatted.isBlank()) {
                continue;
            }
            MessageCreateAction action = ch.sendMessage(formatted);
            action.setAllowedMentions(EnumSet.noneOf(Message.MentionType.class));
            action.queue(
                    ok -> { },
                    err -> LOG.warn("ingame_chat_log Discord send failed: {}", err.toString()));
        }
    }

    private long initOrRepairOffset(String absPath, long fileSize) throws SQLException {
        String storedPath = database.getBotKv(KV_PATH).orElse("");
        long storedOff = database.getBotKvLong(KV_OFFSET).orElse(-1L);
        if (!absPath.equals(storedPath) || storedOff < 0) {
            long start = fileSize;
            database.putBotKv(KV_PATH, absPath);
            database.putBotKv(KV_OFFSET, String.valueOf(start));
            incompleteLine.setLength(0);
            LOG.info("ingame_chat_log: new or changed log path — starting at EOF (offset {}), no backlog", start);
            return start;
        }
        return storedOff;
    }

    private List<String> extractMatchingLines(List<String> markers, boolean purgeParkedOnKill, BotConfig cfg) {
        String s = incompleteLine.toString();
        int lastNl = s.lastIndexOf('\n');
        if (lastNl < 0) {
            return List.of();
        }
        String complete = s.substring(0, lastNl + 1);
        incompleteLine.delete(0, lastNl + 1);

        List<String> out = new ArrayList<>();
        for (String raw : complete.split("\n", -1)) {
            if (raw.isEmpty()) {
                continue;
            }
            if (purgeParkedOnKill && raw.contains("LogTheIsleKillData")) {
                KillLogVictimSteam.extractVictimSteamId64(raw).ifPresent(sid -> {
                    try {
                        int r = database.applyPurgeOnKillForVictimSteam(sid, cfg.dinoParkPurgeOnKillDeathsPerSlot());
                        if (r == 1) {
                            LOG.info("dino_park: removed session parking slot for victim SteamID {} (kill log)", sid);
                        } else if (r == 0) {
                            LOG.debug("dino_park: kill counted for victim SteamID {} (session slot kept until threshold)", sid);
                        }
                    } catch (SQLException e) {
                        LOG.warn("dino_park: purge on kill failed for {}: {}", sid, e.toString());
                    }
                });
            }
            DinoParkLogoutAutosave.handleLogLine(raw, live, database, rcon);
            if (lineMatchesAnyMarker(raw, markers)) {
                out.add(raw);
            }
        }
        return out;
    }

    private static boolean lineMatchesAnyMarker(String raw, List<String> markers) {
        if (markers == null || markers.isEmpty()) {
            return false;
        }
        for (String m : markers) {
            if (m != null && !m.isBlank() && raw.contains(m)) {
                return true;
            }
        }
        return false;
    }

    /** Proximity / local voice chat — not mirrored to Discord when skipped here. */
    private static boolean isProximityChatChannel(String channel) {
        if (channel == null || channel.isEmpty()) {
            return false;
        }
        String c = channel.trim();
        return "Spatial".equalsIgnoreCase(c) || "Local".equalsIgnoreCase(c);
    }

    static String formatForDiscord(String raw, boolean mirrorLocalChat, BotConfig config) {
        String t = raw;
        for (int i = 0; i < 4; i++) {
            String next = LEADING_TIMESTAMP.matcher(t).replaceFirst("").trim();
            if (next.equals(t)) {
                break;
            }
            t = next;
        }
        while (true) {
            Matcher bt = BRACKET_TIME.matcher(t);
            if (!bt.find() || bt.start() != 0) {
                break;
            }
            t = t.substring(bt.end()).trim();
        }
        t = t.replace("Verbose: ", "").trim();

        Matcher chat = LOG_CHAT.matcher(t);
        if (chat.matches()) {
            String channel = chat.group(1).trim();
            if (!mirrorLocalChat && isProximityChatChannel(channel)) {
                return "";
            }
            String name = chat.group(2).trim();
            String message = chat.group(4).trim();
            String line = "[" + escapeMd(channel) + "] **" + escapeMd(name) + ":** " + escapeMd(message);
            return truncateDiscord(line);
        }

        Matcher killAi = LOG_KILL_AI.matcher(t);
        if (killAi.matches()) {
            String tail = killAi.group(1).trim();
            String line = formatKillLine("", tail, config);
            return truncateDiscord(line);
        }

        Matcher kill = LOG_KILL.matcher(t);
        if (kill.matches()) {
            String name = kill.group(1).trim();
            String tail = kill.group(3).trim();
            String line = formatKillLine(name, tail, config);
            return truncateDiscord(line);
        }

        Optional<String> jl = tryFormatJoinLeaveLine(t, config);
        if (jl.isPresent()) {
            return truncateDiscord(jl.get());
        }

        return truncateDiscord(fallbackPrettyLog(t));
    }

    private static boolean useKillFlavor(BotConfig cfg) {
        return cfg != null && cfg.ingameChatLogKillFlavorEnabled() && cfg.killFlavorPack().hasAny();
    }

    /**
     * Formats {@code LogTheIsleJoinData} / {@code LogTheIsleLeaveData} lines for Discord (optional templates from
     * {@code join_quips} / {@code leave_quips} in kill-flavor.yml when kill flavor is enabled).
     */
    private static Optional<String> tryFormatJoinLeaveLine(String stripped, BotConfig config) {
        if (!stripped.contains("LogTheIsleJoinData") && !stripped.contains("LogTheIsleLeaveData")) {
            return Optional.empty();
        }

        Matcher leaveTag = LOG_LEAVE_DATA.matcher(stripped);
        if (leaveTag.matches()) {
            return Optional.of(buildJoinLeaveDiscord(
                    leaveTag.group(1).trim(),
                    leaveTag.group(2).trim(),
                    leaveTag.group(3) == null ? "" : leaveTag.group(3).trim(),
                    true,
                    config));
        }

        Matcher joinTag = LOG_JOIN_DATA.matcher(stripped);
        if (joinTag.matches()) {
            String nameRaw = joinTag.group(1).trim();
            String steam = joinTag.group(2).trim();
            String tail = joinTag.group(3) == null ? "" : joinTag.group(3).trim();
            boolean asLeave = LEAVE_HINT.matcher(stripped).find() || LEAVE_HINT.matcher(tail).find();
            return Optional.of(buildJoinLeaveDiscord(nameRaw, steam, tail, asLeave, config));
        }

        Matcher sm = STEAM_64.matcher(stripped);
        if (!sm.find()) {
            return Optional.empty();
        }
        String steam = sm.group(1);
        String nameGuess = stripped.replaceFirst("(?i)LogTheIsle(Join|Leave)Data:\\s*\\[[^\\]]+\\]\\s*", "");
        nameGuess = nameGuess.replaceAll("\\[7656119\\d{10}\\]", "").trim();
        if (nameGuess.length() > 80) {
            nameGuess = nameGuess.substring(0, 77) + "…";
        }
        boolean leave = LEAVE_HINT.matcher(stripped).find();
        return Optional.of(buildJoinLeaveDiscord(nameGuess.isEmpty() ? "Player" : nameGuess, steam, "", leave, config));
    }

    private static String buildJoinLeaveDiscord(
            String nameRaw,
            String steam64,
            String tailRaw,
            boolean leave,
            BotConfig config
    ) {
        String player = escapeMd(nameRaw.trim());
        String steam = escapeMd(steam64.trim());
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("player", player);
        ph.put("steam", "`" + steam + "`");

        if (useKillFlavor(config)) {
            KillFlavorPack pack = config.killFlavorPack();
            Optional<String> flavored = leave ? pack.rollLeaveLine(ph) : pack.rollJoinLine(ph);
            if (flavored.isPresent()) {
                return flavored.get();
            }
        }

        String tail = tailRaw == null ? "" : escapeMd(tailRaw.trim());
        if (leave) {
            return tail.isEmpty()
                    ? "**" + player + "** left the island"
                    : "**" + player + "** left the island — " + tail;
        }
        return tail.isEmpty()
                ? "**" + player + "** washed ashore"
                : "**" + player + "** joined the island — " + tail;
    }

    private static String formatKillLine(String player, String tail, BotConfig config) {
        String p = escapeMd(player.trim());
        String killerBold = killerBoldMd(p);
        String t = tail.trim();
        if (t.isEmpty()) {
            return "**" + killerBold + "**";
        }

        Matcher pvp = KILL_PVP.matcher(t);
        if (pvp.matches()) {
            return formatPvpKillLine(player, pvp, config);
        }
        Matcher pvp2 = KILL_PVP_NO_PREFIX.matcher(t);
        if (pvp2.matches()) {
            return formatPvpKillLine(player, pvp2, config);
        }

        Matcher nat = KILL_NATURAL.matcher(t);
        if (nat.matches()) {
            String species = escapeMd(nat.group(1).trim());
            String sex = escapeMd(nat.group(2).trim());
            String cause = escapeMd(nat.group(3).trim());
            if (!cause.toLowerCase(Locale.ROOT).contains("killed the following player")) {
                if (useKillFlavor(config)) {
                    Map<String, String> ph = new LinkedHashMap<>();
                    ph.put("victim", killerBold);
                    ph.put("species", species);
                    ph.put("sex", sex);
                    ph.put("cause", cause);
                    Optional<String> flavored = config.killFlavorPack().rollNaturalLine(ph);
                    if (flavored.isPresent()) {
                        return flavored.get();
                    }
                }
                return "**" + killerBold + "** — " + species + " (" + sex + ") — " + cause;
            }
        }

        String rest = t.replaceAll("(?i)\\[7656119\\d{10}\\]", "")
                .replaceAll("(?i),?\\s*at:\\s*X=[^\\s]+\\s*Y=[^\\s]+\\s*Z=[^\\s]+", "")
                .replaceAll("(?i)\\s*Growth:\\s*\\S+", "")
                .replaceAll("\\s+", " ")
                .trim();
        return "**" + killerBold + "** — " + escapeMd(rest);
    }

    /** Empty killer display name (AI / {@code []} in log) → bold {@code AI}. */
    private static String killerBoldMd(String escapedPlayerName) {
        return escapedPlayerName.isEmpty() ? escapeMd("AI") : escapedPlayerName;
    }

    private static String formatPvpKillLine(String playerRaw, Matcher m, BotConfig config) {
        String kSpeciesRaw = m.group(1).trim();
        String kSpecies = escapeMd(kSpeciesRaw);
        String kSex = escapeMd(m.group(2).trim());
        String vName = escapeMd(m.group(3).trim());
        String vSpeciesRaw = m.group(4).trim();
        String vSpecies = escapeMd(vSpeciesRaw);
        String vGender = escapeMd(m.group(5).trim());
        String killerEscaped = escapeMd(playerRaw.trim());
        String kBold = killerBoldMd(killerEscaped);
        boolean aiKiller = playerRaw.trim().isEmpty();

        if (useKillFlavor(config)) {
            KillFlavorPack pack = config.killFlavorPack();
            Map<String, String> ph = new LinkedHashMap<>();
            ph.put("killer", kBold);
            ph.put("victim", vName);
            ph.put("killerSpecies", kSpecies);
            ph.put("killerSex", kSex);
            ph.put("victimSpecies", vSpecies);
            ph.put("victimGender", vGender);
            Optional<String> flavored = pack.rollPvpLine(aiKiller, kSpeciesRaw, vSpeciesRaw, ph);
            if (flavored.isPresent()) {
                return flavored.get();
            }
        }
        if (aiKiller) {
            return "**" + kBold + "** (" + kSpecies + ") → **" + vName + "** (" + vSpecies + ", " + vGender + ")";
        }
        return "**" + kBold + "** (" + kSpecies + ", " + kSex + ") → **" + vName + "** (" + vSpecies + ", " + vGender + ")";
    }

    /** Strip log tags / SteamIDs; used when a line matched filters but not our strict parsers. */
    private static String fallbackPrettyLog(String t) {
        String u = t.replaceAll("(?i)LogTheIsle\\w+Data:\\s*", "");
        u = u.replaceAll("(?i)^\\[\\d{4}\\.\\d{2}\\.\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}:\\d{3}\\]\\s*\\[\\s*\\d+\\s*\\]\\s*", "");
        u = u.replaceAll("\\[7656119\\d{10}\\]", "");
        u = u.replaceAll("\\[GROUP-[^\\]]+\\]", "");
        u = u.replaceAll("\\[\\d{4}\\.\\d{2}\\.\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\]", "");
        u = u.replaceAll("\\[\\d{4}\\.\\d{2}\\.\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}:\\d{3}\\]", "");
        u = u.replaceAll("\\s+", " ").trim();
        u = u.replace("@everyone", "@\u200beveryone").replace("@here", "@\u200bhere");
        return u;
    }

    private static String escapeMd(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                case '*':
                case '_':
                case '~':
                case '`':
                case '|':
                    b.append('\\').append(c);
                    break;
                case '\r':
                case '\n':
                    b.append(' ');
                    break;
                default:
                    if (c == '@') {
                        b.append("@\u200b");
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }

    private static String truncateDiscord(String t) {
        if (t.length() <= MAX_DISCORD) {
            return t;
        }
        return t.substring(0, MAX_DISCORD - 1) + "…";
    }
}
