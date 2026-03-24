package com.isle.evrima.bot.rcon;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls an IPv4 from RCON {@code getplayerdata} text when the server includes one (field names vary by build).
 */
public final class PlayerdataIpExtract {

    private PlayerdataIpExtract() {}

    private static final Pattern LABELED_IPV4 = Pattern.compile(
            "(?i)(?:IP(?:v4)?|IPAddress|ClientIP|RemoteIP|NetAddr(?:ess)?|NetworkAddress|Conn(?:ection)?IP)\\s*[:=]\\s*"
                    + "\\b((?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?))\\b");

    private static final Pattern ANY_IPV4 = Pattern.compile(
            "\\b((?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?))\\b");

    /**
     * Prefer an explicitly labeled IPv4; otherwise the first non-{@code 0.0.0.0} dotted quad in the snippet.
     */
    public static Optional<String> preferredIpv4(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher labeled = LABELED_IPV4.matcher(text);
        if (labeled.find()) {
            return Optional.of(labeled.group(1));
        }
        Matcher any = ANY_IPV4.matcher(text);
        while (any.find()) {
            String ip = any.group(1);
            if (!"0.0.0.0".equals(ip)) {
                return Optional.of(ip);
            }
        }
        return Optional.empty();
    }
}
