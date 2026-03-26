package com.isle.evrima.bot.dino;

import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON stored in {@code parked_dinos.payload_json}: live {@code getplayerdata} snapshot for a slot.
 * Optional {@code diskUtf8B64} holds a byte-identical copy of the on-disk player file at park time when configured.
 */
public final class ParkedDinoPayload {

    public static final int FORMAT_VERSION = 3;

    private static final Pattern CLASS_P = Pattern.compile("(?i)Class:\\s*([^,\\n]+)");
    private static final Pattern GROWTH_P = Pattern.compile("(?i)Growth:\\s*([^,\\n]+)");
    private static final Pattern GENDER_P = Pattern.compile("(?i)Gender:\\s*([^,\\n]+)");
    private static final Pattern JSON_CLASS = Pattern.compile("\"class\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern JSON_GROWTH = Pattern.compile("\"growth\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern JSON_GENDER = Pattern.compile("\"gender\"\\s*:\\s*\"([^\"]*)\"");

    private ParkedDinoPayload() {}

    public record Summary(String dinoClass, String growth, String gender) {
        public String oneLine() {
            StringBuilder sb = new StringBuilder();
            if (dinoClass != null && !dinoClass.isBlank()) {
                sb.append(dinoClass.strip());
            }
            if (growth != null && !growth.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append("growth ").append(growth.strip());
            }
            if (gender != null && !gender.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append(gender.strip());
            }
            return sb.isEmpty() ? "—" : sb.toString();
        }
    }

    public static Summary parseSummaryFromGetplayerdata(String filteredBody) {
        if (filteredBody == null || filteredBody.isBlank()) {
            return new Summary(null, null, null);
        }
        return new Summary(
                find(CLASS_P, filteredBody),
                find(GROWTH_P, filteredBody),
                find(GENDER_P, filteredBody));
    }

    private static String find(Pattern p, String hay) {
        Matcher m = p.matcher(hay);
        return m.find() ? m.group(1).strip() : null;
    }

    public static String buildJson(long capturedAtEpochSec, String rawFiltered, Summary s) {
        return buildJson(capturedAtEpochSec, rawFiltered, s, Optional.empty());
    }

    /**
     * @param diskUtf8 optional UTF-8 (or exact server file bytes if already valid UTF-8) snapshot of the playerdata file
     */
    public static String buildJson(long capturedAtEpochSec, String rawFiltered, Summary s, Optional<byte[]> diskUtf8) {
        String tail = ",\"class\":"
                + jsonString(s.dinoClass() == null ? "" : s.dinoClass())
                + ",\"growth\":"
                + jsonString(s.growth() == null ? "" : s.growth())
                + ",\"gender\":"
                + jsonString(s.gender() == null ? "" : s.gender());
        if (diskUtf8.isPresent() && diskUtf8.get().length > 0) {
            String b64 = Base64.getEncoder().encodeToString(diskUtf8.get());
            tail += ",\"diskUtf8B64\":" + jsonString(b64);
        }
        return "{\"v\":"
                + FORMAT_VERSION
                + ",\"capturedAtEpochSec\":"
                + capturedAtEpochSec
                + ",\"raw\":"
                + jsonString(rawFiltered == null ? "" : rawFiltered)
                + tail
                + "}";
    }

    public static Summary readSummaryFromStoredJson(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return new Summary(null, null, null);
        }
        String j = payloadJson;
        return new Summary(
                group1(JSON_CLASS, j),
                group1(JSON_GROWTH, j),
                group1(JSON_GENDER, j));
    }

    private static String group1(Pattern p, String hay) {
        Matcher m = p.matcher(hay);
        return m.find() ? m.group(1) : null;
    }

    public static Optional<String> readRawFromStoredJson(String payloadJson) {
        if (payloadJson == null) {
            return Optional.empty();
        }
        int key = payloadJson.indexOf("\"raw\":");
        if (key < 0) {
            return Optional.empty();
        }
        int start = payloadJson.indexOf('"', key + 6);
        if (start < 0) {
            return Optional.empty();
        }
        StringBuilder out = new StringBuilder();
        for (int i = start + 1; i < payloadJson.length(); i++) {
            char c = payloadJson.charAt(i);
            if (c == '\\' && i + 1 < payloadJson.length()) {
                char n = payloadJson.charAt(i + 1);
                if (n == 'n') {
                    out.append('\n');
                    i++;
                    continue;
                }
                if (n == 'r') {
                    i++;
                    continue;
                }
                if (n == '"' || n == '\\') {
                    out.append(n);
                    i++;
                    continue;
                }
                out.append(c);
                continue;
            }
            if (c == '"') {
                break;
            }
            out.append(c);
        }
        String s = out.toString().strip();
        return s.isEmpty() ? Optional.empty() : Optional.of(s);
    }

    static String jsonString(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    b.append("\\\\");
                    break;
                case '"':
                    b.append("\\\"");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
        return b.toString();
    }
}
