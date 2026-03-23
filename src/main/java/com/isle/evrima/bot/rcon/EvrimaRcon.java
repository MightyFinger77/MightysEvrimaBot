package com.isle.evrima.bot.rcon;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * The Isle Evrima binary RCON: {@code 0x01 + password + 0} to authenticate, then
 * {@code 0x02 + opcode + payload + 0} per command. This is not Source/mcrcon wire format.
 *
 * @see <a href="https://github.com/modernham/The-Isle-Evrima-Server-Tools">The-Isle-Evrima-Server-Tools</a>
 * @see <a href="https://github.com/theislemanager/evrima-rcon/blob/main/src/rcon.php">evrima-rcon</a>
 * @see <a href="https://github.com/smultar-dev/evrima.rcon">smultar-dev/evrima.rcon</a> (TypeScript client — same opcodes
 *      for the commands it implements; uses names like {@code players}, {@code srv:details}, {@code ai:toggle})
 * <p>
 * Only **named Evrima RCON commands** from that family are wired here — no {@code custom} free-text passthrough.
 * {@link #normalizeCommandName(String)} accepts smultar-style aliases ({@code ai:density}, {@code entities:wipe:corpses}, …).
 * <p>
 * <b>Multi-field payloads:</b> {@code kick}, {@code ban}, and {@code directmessage} use <b>commas</b> between fields
 * on the wire (same as hosting-panel docs), not spaces — use {@link #lineKick}, {@link #lineBan}, {@link #lineDirectMessage}.
 */
public final class EvrimaRcon implements AutoCloseable {

    /**
     * Command name → Evrima RCON opcode byte. Names match community opcode tables
     * (theislemanager/evrima-rcon, hosting docs). No public 0x85 in those charts.
     */
    private static final Map<String, Integer> COMMANDS = Map.ofEntries(
            Map.entry("announce", 0x10),
            Map.entry("directmessage", 0x11),
            Map.entry("serverdetails", 0x12),
            Map.entry("wipecorpses", 0x13),
            Map.entry("getplayables", 0x14),
            Map.entry("updateplayables", 0x15),
            Map.entry("togglemigrations", 0x19),
            Map.entry("ban", 0x20),
            Map.entry("togglegrowthmultiplier", 0x21),
            Map.entry("setgrowthmultiplier", 0x22),
            Map.entry("togglenetupdatedistancechecks", 0x23),
            Map.entry("kick", 0x30),
            Map.entry("playerlist", 0x40),
            Map.entry("save", 0x50),
            Map.entry("pause", 0x60),
            Map.entry("getplayerdata", 0x77),
            Map.entry("togglewhitelist", 0x81),
            Map.entry("addwhitelist", 0x82),
            Map.entry("removewhitelist", 0x83),
            Map.entry("toggleglobalchat", 0x84),
            Map.entry("togglehumans", 0x86),
            Map.entry("toggleai", 0x90),
            Map.entry("disableaiclasses", 0x91),
            Map.entry("aidensity", 0x92),
            Map.entry("getqueuestatus", 0x93),
            Map.entry("toggleailearning", 0x94));

    /**
     * {@code kick SteamID64,Reason} — commas separate fields (Evrima RCON / panel docs).
     */
    public static String lineKick(String steamId64, String reason) {
        return "kick " + steamId64.trim() + "," + sanitizeCommaField(reason);
    }

    /**
     * {@code ban Name,SteamID64,Reason,Minutes} — four comma-separated fields (host-panel style).
     * smultar’s TS README sometimes shows a shorter {@code ban} form; we keep four fields for parity with major host docs.
     */
    public static String lineBan(String playerName, String steamId64, String reason, int banMinutes) {
        return "ban "
                + sanitizeCommaField(playerName) + ","
                + steamId64.trim() + ","
                + sanitizeCommaField(reason) + ","
                + banMinutes;
    }

    /**
     * {@code directmessage SteamID64,Message} — comma after SteamID64; message is the rest.
     */
    public static String lineDirectMessage(String steamId64, String message) {
        return "directmessage " + steamId64.trim() + "," + sanitizeCommaField(message);
    }

    /** {@code aidensity value} — plain decimal string (no scientific notation). */
    public static String lineAidensity(double value) {
        return "aidensity " + BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /**
     * Single-line text for RCON comma-delimited fields: newlines → spaces, commas → {@code ;} so fields don’t split wrong.
     */
    public static String sanitizeCommaField(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\r', ' ')
                .replace('\n', ' ')
                .replace(',', ';')
                .trim();
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    public EvrimaRcon(String host, int port, int timeoutMs) throws IOException {
        int connectMs = Math.min(Math.max(timeoutMs, 1_000), 15_000);
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectMs);
        socket.setSoTimeout(timeoutMs);
        in = socket.getInputStream();
        out = socket.getOutputStream();
    }

    /**
     * One full session: connect, authenticate, send one command, read response, close.
     *
     * @param commandLine first token = command name (e.g. {@code playerlist}); rest = UTF-8 payload
     */
    public static String send(String host, int port, String password, String commandLine, int timeoutMs)
            throws IOException {
        String line = commandLine.trim();
        if (line.isEmpty()) {
            throw new IOException("Empty RCON command");
        }
        int sp = line.indexOf(' ');
        String name = normalizeCommandName((sp < 0 ? line : line.substring(0, sp)).toLowerCase(Locale.ROOT).trim());
        String data = sp < 0 ? "" : line.substring(sp + 1).trim();
        Integer op = COMMANDS.get(name);
        if (op == null) {
            throw new IOException("Unknown Evrima RCON command: " + name);
        }
        try (EvrimaRcon r = new EvrimaRcon(host, port, timeoutMs)) {
            r.authenticate(password);
            return r.exec(op, data);
        }
    }

    private void authenticate(String password) throws IOException {
        if (password == null) {
            throw new IOException("RCON password is null");
        }
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream auth = new ByteArrayOutputStream(2 + pw.length);
        auth.write(0x01);
        auth.write(pw);
        auth.write(0);
        out.write(auth.toByteArray());
        out.flush();
        String response = readResponse();
        if (!response.toLowerCase(Locale.ROOT).contains("accepted")) {
            throw new IOException("RCON auth failed: " + truncate(response, 200));
        }
    }

    private String exec(int commandByte, String commandData) throws IOException {
        byte[] payload = commandData.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream pkt = new ByteArrayOutputStream(2 + payload.length);
        pkt.write(0x02);
        pkt.write(commandByte);
        pkt.write(payload);
        pkt.write(0);
        out.write(pkt.toByteArray());
        out.flush();
        return readResponse();
    }

    /** Read until EOF or socket read timeout (idle gap). */
    private String readResponse() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        while (true) {
            try {
                int n = in.read(chunk);
                if (n == -1) {
                    break;
                }
                buf.write(chunk, 0, n);
            } catch (SocketTimeoutException e) {
                break;
            }
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    /**
     * Maps shorthand and <a href="https://github.com/smultar-dev/evrima.rcon">smultar-dev/evrima.rcon</a> command names
     * to the internal opcode map keys ({@code announce}, {@code playerlist}, …).
     */
    static String normalizeCommandName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        return switch (name) {
            case "list", "players" -> "playerlist";
            case "dm" -> "directmessage";
            case "serverinfo", "srv:details" -> "serverdetails";
            case "entities:wipe:corpses" -> "wipecorpses";
            case "playdata" -> "getplayerdata";
            case "whitelist:toggle" -> "togglewhitelist";
            case "whitelist:add" -> "addwhitelist";
            case "whitelist:remove" -> "removewhitelist";
            case "globalchat:toggle" -> "toggleglobalchat";
            case "humans:toggle" -> "togglehumans";
            case "ai:toggle" -> "toggleai";
            case "ai:classes:disable" -> "disableaiclasses";
            case "ai:density" -> "aidensity";
            case "disableai" -> "disableaiclasses";
            default -> name;
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\r', ' ').replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
