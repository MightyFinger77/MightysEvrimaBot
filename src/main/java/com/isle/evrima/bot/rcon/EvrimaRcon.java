package com.isle.evrima.bot.rcon;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The Isle Evrima binary RCON: {@code 0x01 + password + 0} to authenticate, then
 * {@code 0x02 + opcode + payload + 0} per command. This is not Source/mcrcon wire format.
 *
 * @see <a href="https://github.com/modernham/The-Isle-Evrima-Server-Tools">The-Isle-Evrima-Server-Tools</a>
 * @see <a href="https://github.com/theislemanager/evrima-rcon/blob/main/src/rcon.php">evrima-rcon</a>
 */
public final class EvrimaRcon implements AutoCloseable {

    private static final Map<String, Integer> COMMANDS = new HashMap<>();

    static {
        COMMANDS.put("announce", 0x10);
        COMMANDS.put("directmessage", 0x11);
        COMMANDS.put("serverdetails", 0x12);
        COMMANDS.put("wipecorpses", 0x13);
        COMMANDS.put("getplayables", 0x14);
        COMMANDS.put("updateplayables", 0x15);
        COMMANDS.put("togglemigrations", 0x19);
        COMMANDS.put("ban", 0x20);
        COMMANDS.put("togglegrowthmultiplier", 0x21);
        COMMANDS.put("setgrowthmultiplier", 0x22);
        COMMANDS.put("togglenetupdatedistancechecks", 0x23);
        COMMANDS.put("kick", 0x30);
        COMMANDS.put("playerlist", 0x40);
        COMMANDS.put("save", 0x50);
        COMMANDS.put("pause", 0x60);
        COMMANDS.put("custom", 0x70);
        COMMANDS.put("getplayerdata", 0x77);
        COMMANDS.put("togglewhitelist", 0x81);
        COMMANDS.put("addwhitelist", 0x82);
        COMMANDS.put("removewhitelist", 0x83);
        COMMANDS.put("toggleglobalchat", 0x84);
        COMMANDS.put("togglehumans", 0x86);
        COMMANDS.put("toggleai", 0x90);
        COMMANDS.put("disableaiclasses", 0x91);
        COMMANDS.put("aidensity", 0x92);
        COMMANDS.put("getqueuestatus", 0x93);
        COMMANDS.put("toggleailearning", 0x94);
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
        String name = (sp < 0 ? line : line.substring(0, sp)).toLowerCase(Locale.ROOT).trim();
        String data = sp < 0 ? "" : line.substring(sp + 1).trim();
        if ("list".equals(name)) {
            name = "playerlist";
        }
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
