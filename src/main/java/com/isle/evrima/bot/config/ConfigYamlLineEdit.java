package com.isle.evrima.bot.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * In-place line edits under a single top-level YAML section so the rest of {@code config.yml} (banner, comments,
 * key order, flow style in other sections) is untouched.
 */
final class ConfigYamlLineEdit {

    private ConfigYamlLineEdit() {}

    static void writeLinesAtomic(Path path, List<String> lines) throws IOException {
        String body = String.join("\n", lines);
        if (!body.isEmpty() && !body.endsWith("\n")) {
            body = body + "\n";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static List<String> readLines(Path path) throws IOException {
        return new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
    }

    static void assertValidYaml(Path path) throws IOException {
        try (var in = Files.newInputStream(path)) {
            new Yaml().load(in);
        }
    }

    static int leadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    static boolean isTopLevelKeyLine(String line) {
        if (line.isEmpty() || line.charAt(0) == ' ' || line.charAt(0) == '\t') {
            return false;
        }
        int colon = line.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        String key = line.substring(0, colon).trim();
        return key.matches("[A-Za-z0-9_-]+");
    }

    /**
     * @return {@code [sectionHeaderLineIndex, sectionEndExclusive]}
     */
    static int[] findTopLevelSection(List<String> lines, String sectionKey) throws IOException {
        String prefix = sectionKey + ":";
        for (int i = 0; i < lines.size(); i++) {
            String L = lines.get(i);
            if (leadingSpaces(L) != 0) {
                continue;
            }
            String t = L.trim();
            if (t.equals(prefix) || t.startsWith(prefix + " ") || t.startsWith(prefix + "\t")) {
                int end = lines.size();
                for (int j = i + 1; j < lines.size(); j++) {
                    String L2 = lines.get(j);
                    if (leadingSpaces(L2) == 0 && isTopLevelKeyLine(L2)) {
                        end = j;
                        break;
                    }
                }
                return new int[]{i, end};
            }
        }
        throw new IOException("Top-level section not found: " + sectionKey);
    }

    static int sectionBodyIndent(List<String> lines, int bodyStart, int bodyEnd) throws IOException {
        for (int j = bodyStart; j < bodyEnd; j++) {
            String L = lines.get(j);
            String ts = L.strip();
            if (ts.isEmpty() || ts.startsWith("#")) {
                continue;
            }
            int ls = leadingSpaces(L);
            if (ls == 0) {
                continue;
            }
            return ls;
        }
        throw new IOException("Empty section body (no indented keys)");
    }

    static void replaceKeyedScalarInRange(List<String> lines, int rangeStart, int rangeEnd, int keyIndent, String yamlKey, String newValueYaml) throws IOException {
        String keyPrefix = yamlKey + ":";
        for (int j = rangeStart; j < rangeEnd; j++) {
            String L = lines.get(j);
            String ts = L.strip();
            if (ts.isEmpty() || ts.startsWith("#")) {
                continue;
            }
            if (leadingSpaces(L) != keyIndent) {
                continue;
            }
            if (ts.startsWith(keyPrefix)) {
                lines.set(j, rebuildKeyedLine(L, newValueYaml));
                return;
            }
        }
        throw new IOException("Key not found in section: " + yamlKey);
    }

    /**
     * Rebuilds {@code key: <new scalar>} preserving trailing inline {@code # comment} if present.
     */
    static String rebuildKeyedLine(String originalLine, String newValueYaml) {
        int hash = indexOfUnquotedHash(originalLine);
        String tailComment = "";
        String work = originalLine;
        if (hash >= 0) {
            tailComment = originalLine.substring(hash).stripLeading();
            work = originalLine.substring(0, hash).stripTrailing();
        }
        int colon = work.indexOf(':');
        if (colon < 0) {
            return originalLine;
        }
        String keyPart = work.substring(0, colon + 1).stripTrailing();
        String rebuilt = keyPart + " " + newValueYaml;
        if (!tailComment.isEmpty()) {
            rebuilt = rebuilt + " " + tailComment;
        }
        return rebuilt;
    }

    private static int indexOfUnquotedHash(String s) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble) {
                return i;
            }
        }
        return -1;
    }

    static String yamlQuoteIfNeeded(String raw) {
        if (raw == null) {
            return "\"\"";
        }
        String s = raw;
        if (s.isEmpty()) {
            return "\"\"";
        }
        boolean safe = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= ' ' || c == ':' || c == '#' || c == '"' || c == '\'' || c == '[' || c == ']' || c == '{' || c == '}') {
                safe = false;
                break;
            }
        }
        if (safe) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    static String formatScalar(Object v) {
        if (v == null) {
            return "\"\"";
        }
        if (v instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (v instanceof Number n) {
            return n.toString();
        }
        return yamlQuoteIfNeeded(String.valueOf(v));
    }

    // --- species_population_control ---

    static void setSpeciesPopulationControlEnabled(Path path, boolean enabled) throws IOException {
        List<String> lines = readLines(path);
        int[] sec = findTopLevelSection(lines, "species_population_control");
        int bodyStart = sec[0] + 1;
        int bodyEnd = sec[1];
        int indent = sectionBodyIndent(lines, bodyStart, bodyEnd);
        replaceKeyedScalarInRange(lines, bodyStart, bodyEnd, indent, "enabled", enabled ? "true" : "false");
        writeLinesAtomic(path, lines);
        assertValidYaml(path);
    }

    static void setSpeciesCap(Path path, String species, int cap, String insertKeyIfNew) throws IOException {
        Objects.requireNonNull(species, "species");
        String sp = species.trim();
        if (sp.isEmpty()) {
            throw new IOException("species is empty");
        }
        String keyForNewLine = (insertKeyIfNew != null && !insertKeyIfNew.isBlank()) ? insertKeyIfNew.trim() : sp;

        List<String> lines = readLines(path);
        int[] sec = findTopLevelSection(lines, "species_population_control");
        int bodyStart = sec[0] + 1;
        int bodyEnd = sec[1];
        int sectionIndent = sectionBodyIndent(lines, bodyStart, bodyEnd);

        int capsLine = -1;
        int capsIndent = -1;
        for (int j = bodyStart; j < bodyEnd; j++) {
            String L = lines.get(j);
            String ts = L.strip();
            if (ts.isEmpty() || ts.startsWith("#")) {
                continue;
            }
            if (leadingSpaces(L) != sectionIndent) {
                continue;
            }
            if (ts.equals("caps:") || ts.startsWith("caps:")) {
                capsLine = j;
                capsIndent = leadingSpaces(L);
                break;
            }
        }
        if (capsLine < 0) {
            throw new IOException("species_population_control.caps not found");
        }

        int capEntryIndent = -1;
        for (int j = capsLine + 1; j < bodyEnd; j++) {
            String L = lines.get(j);
            String ts = L.strip();
            if (ts.isEmpty() || ts.startsWith("#")) {
                continue;
            }
            int ls = leadingSpaces(L);
            if (ls <= capsIndent) {
                break;
            }
            capEntryIndent = ls;
            break;
        }
        if (capEntryIndent < 0) {
            capEntryIndent = capsIndent + 2;
        }

        int insertPos = capsLine + 1;
        for (int j = capsLine + 1; j < bodyEnd; j++) {
            String L = lines.get(j);
            String ts = L.strip();
            if (ts.isEmpty() || ts.startsWith("#")) {
                continue;
            }
            int ls = leadingSpaces(L);
            if (ls <= capsIndent) {
                break;
            }
            if (ls != capEntryIndent) {
                continue;
            }
            String capKey = parseIndentedKey(L);
            if (capKey == null) {
                continue;
            }
            insertPos = j + 1;
            if (capKey.equalsIgnoreCase(sp)) {
                lines.set(j, rebuildKeyedLine(L, String.valueOf(cap)));
                writeLinesAtomic(path, lines);
                assertValidYaml(path);
                return;
            }
        }

        String indentStr = " ".repeat(capEntryIndent);
        lines.add(insertPos, indentStr + keyForNewLine + ": " + cap);
        writeLinesAtomic(path, lines);
        assertValidYaml(path);
    }

    private static String parseIndentedKey(String line) {
        int colon = line.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String keyPart = line.substring(0, colon).strip();
        if (keyPart.isEmpty() || keyPart.startsWith("#")) {
            return null;
        }
        return keyPart;
    }

    // --- scheduled_wipecorpses ---

    static void setScheduledWipecorpsesField(Path path, String yamlKey, Object value) throws IOException {
        List<String> lines = readLines(path);
        int[] sec = findTopLevelSection(lines, "scheduled_wipecorpses");
        int bodyStart = sec[0] + 1;
        int bodyEnd = sec[1];
        int indent = sectionBodyIndent(lines, bodyStart, bodyEnd);
        replaceKeyedScalarInRange(lines, bodyStart, bodyEnd, indent, yamlKey, formatScalar(value));
        writeLinesAtomic(path, lines);
        assertValidYaml(path);
    }

    static void mergeScheduledWipecorpsesFromExample(Path path, Map<String, Object> exWipe) throws IOException {
        List<String> lines = readLines(path);
        int[] sec = findTopLevelSection(lines, "scheduled_wipecorpses");
        int bodyStart = sec[0] + 1;
        int insertEnd = sec[1];
        int indent = sectionBodyIndent(lines, bodyStart, insertEnd);
        for (Map.Entry<String, Object> e : exWipe.entrySet()) {
            String k = e.getKey();
            if (k == null || k.isBlank()) {
                continue;
            }
            try {
                replaceKeyedScalarInRange(lines, bodyStart, insertEnd, indent, k, formatScalar(e.getValue()));
            } catch (IOException missing) {
                lines.add(insertEnd, " ".repeat(indent) + k + ": " + formatScalar(e.getValue()));
                insertEnd++;
            }
        }
        writeLinesAtomic(path, lines);
        assertValidYaml(path);
    }
}
