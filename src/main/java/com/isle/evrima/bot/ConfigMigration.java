package com.isle.evrima.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Merges the on-disk {@code config.yml} with the bundled default template ({@code config.yml} on the classpath):
 * keeps the user's preamble (lines before the first top-level key, e.g. a custom banner), then merges body lines
 * from the bundled file — comments and key order from the template, user values substituted where paths match,
 * new template keys added when missing, and {@code config_version} updated. Same idea as Locktight-style migration.
 * <p>
 * Top-level keys that exist only in the user file (not in the bundled template) are not emitted; extend the bundled
 * file in source if you need new official keys.
 */
public final class ConfigMigration {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigMigration.class);
    private static final String BUNDLED_DEFAULT = "config.yml";
    private static final String VERSION_KEY = "config_version";

    private ConfigMigration() {}

    /**
     * If the file is missing, does nothing. On any error, logs a warning and returns without throwing
     * (startup continues with whatever is on disk).
     */
    public static void migrateIfNeeded(Path configYamlPath) {
        Objects.requireNonNull(configYamlPath, "configYamlPath");
        try {
            migrateIfNeededOrThrow(configYamlPath);
        } catch (Exception e) {
            LOG.warn("Config migration skipped or failed (using existing file as-is): {}", e.getMessage());
            LOG.debug("Config migration detail", e);
        }
    }

    @SuppressWarnings("unchecked")
    static void migrateIfNeededOrThrow(Path configYamlPath) throws IOException {
        if (!Files.isRegularFile(configYamlPath)) {
            return;
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        InputStream defaultStream = cl != null ? cl.getResourceAsStream(BUNDLED_DEFAULT) : null;
        if (defaultStream == null) {
            defaultStream = ConfigMigration.class.getClassLoader().getResourceAsStream(BUNDLED_DEFAULT);
        }
        if (defaultStream == null) {
            LOG.warn("Bundled {} not found on classpath — config migration skipped", BUNDLED_DEFAULT);
            return;
        }

        List<String> currentLines = Files.readAllLines(configYamlPath, StandardCharsets.UTF_8);
        List<String> defaultLines = readAllLines(defaultStream);

        Map<String, Object> defaultMap;
        try (InputStream in2 = openBundledDefaultStream()) {
            if (in2 == null) {
                return;
            }
            Object loaded = new Yaml().load(in2);
            defaultMap = loaded instanceof Map ? (Map<String, Object>) loaded : new LinkedHashMap<>();
        }
        Map<String, Object> currentMap;
        try (InputStream in = Files.newInputStream(configYamlPath)) {
            Object loaded = new Yaml().load(in);
            currentMap = loaded instanceof Map ? (Map<String, Object>) loaded : new LinkedHashMap<>();
        }

        int defaultVersion = intFromMap(defaultMap, VERSION_KEY, 1);
        int currentVersion = currentMap.containsKey(VERSION_KEY) ? intFromMap(currentMap, VERSION_KEY, 0) : 0;
        boolean missingLeaves = hasMissingLeafKeysComparedToDefaults(currentMap, defaultMap);
        if (currentVersion == defaultVersion && currentMap.containsKey(VERSION_KEY) && !missingLeaves) {
            return;
        }

        int userKeyStart = indexOfFirstTopLevelKeyLine(currentLines);
        int defaultKeyStart = indexOfFirstTopLevelKeyLine(defaultLines);
        List<String> userHeader = currentLines.subList(0, userKeyStart);
        List<String> defaultBody = defaultLines.subList(defaultKeyStart, defaultLines.size());
        List<String> mergedBody = mergeConfigLines(defaultBody, currentMap, defaultMap);
        Set<String> deprecated = findDeprecatedKeys(currentMap, defaultMap);
        if (!deprecated.isEmpty()) {
            LOG.info("Config migration: keys in your file with no path in the bundled template (omitted from merged output): {}",
                    String.join(", ", deprecated));
        }
        updateConfigVersionLine(mergedBody, defaultVersion, defaultLines);

        List<String> out = new ArrayList<>(userHeader.size() + mergedBody.size());
        out.addAll(userHeader);
        out.addAll(mergedBody);
        Files.write(configYamlPath, out, StandardCharsets.UTF_8);
        LOG.info("Config migration completed — config_version -> {}; preamble preserved; body merged with bundled template.",
                defaultVersion);
    }

    private static InputStream openBundledDefaultStream() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        InputStream in = cl != null ? cl.getResourceAsStream(BUNDLED_DEFAULT) : null;
        if (in == null) {
            in = ConfigMigration.class.getClassLoader().getResourceAsStream(BUNDLED_DEFAULT);
        }
        return in;
    }

    private static List<String> readAllLines(InputStream in) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static int intFromMap(Map<String, Object> map, String key, int defaultVal) {
        Object v = map.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return defaultVal;
            }
        }
        return defaultVal;
    }

    private static boolean hasMissingLeafKeysComparedToDefaults(Map<String, Object> current, Map<String, Object> defaults) {
        List<String> missing = new ArrayList<>();
        collectLeafPaths("", defaults, path -> {
            if (path.equals(VERSION_KEY)) {
                return;
            }
            if (!containsPath(current, path)) {
                missing.add(path);
            }
        });
        if (!missing.isEmpty()) {
            LOG.info("Config migration: merging missing keys from bundled defaults: {}", String.join(", ", missing));
            return true;
        }
        return false;
    }

    @FunctionalInterface
    private interface LeafConsumer {
        void accept(String fullPath);
    }

    @SuppressWarnings("unchecked")
    private static void collectLeafPaths(String prefix, Object node, LeafConsumer consumer) {
        if (!(node instanceof Map<?, ?> raw)) {
            return;
        }
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            String key = String.valueOf(e.getKey());
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object v = e.getValue();
            if (v instanceof Map<?, ?>) {
                collectLeafPaths(path, v, consumer);
            } else {
                consumer.accept(path);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean containsPath(Map<String, Object> root, String path) {
        String[] parts = path.split("\\.");
        Object cur = root;
        for (String p : parts) {
            if (!(cur instanceof Map<?, ?> m)) {
                return false;
            }
            if (!m.containsKey(p)) {
                return false;
            }
            cur = m.get(p);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Object getAtPath(Map<String, Object> root, String path) {
        String[] parts = path.split("\\.");
        Object cur = root;
        for (String p : parts) {
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = m.get(p);
            if (cur == null && !m.containsKey(p)) {
                return null;
            }
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> findDeprecatedKeys(Map<String, Object> user, Map<String, Object> defaults) {
        Set<String> deprecated = new HashSet<>();
        findDeprecatedRecursive(user, defaults, "", deprecated);
        return deprecated;
    }

    @SuppressWarnings("unchecked")
    private static void findDeprecatedRecursive(Map<String, Object> userSection, Map<String, Object> defaultSection,
                                              String basePath, Set<String> deprecated) {
        for (String key : userSection.keySet()) {
            String fullPath = basePath.isEmpty() ? key : basePath + "." + key;
            if (key.equals(VERSION_KEY)) {
                continue;
            }
            if (!defaultSection.containsKey(key)) {
                deprecated.add(fullPath);
            } else {
                Object u = userSection.get(key);
                Object d = defaultSection.get(key);
                if (u instanceof Map<?, ?> um && d instanceof Map<?, ?> dm) {
                    findDeprecatedRecursive((Map<String, Object>) um, (Map<String, Object>) dm, fullPath, deprecated);
                }
            }
        }
    }

    private static List<String> mergeConfigLines(List<String> defaultLines, Map<String, Object> userMap,
                                                 Map<String, Object> defaultMap) {
        List<String> merged = new ArrayList<>();
        ArrayDeque<PathEntry> pathStack = new ArrayDeque<>();

        for (int i = 0; i < defaultLines.size(); i++) {
            String line = defaultLines.get(i);
            String trimmed = line.trim();
            int currentIndent = line.length() - trimmed.length();

            if (trimmed.isEmpty() || line.startsWith("#")) {
                merged.add(line);
                continue;
            }

            while (!pathStack.isEmpty() && currentIndent <= pathStack.peekLast().indent) {
                pathStack.removeLast();
            }

            if (trimmed.startsWith("-")) {
                merged.add(line);
                continue;
            }

            if (trimmed.contains(":") && !trimmed.startsWith("#")) {
                int colonIndex = trimmed.indexOf(':');
                String keyPart = trimmed.substring(0, colonIndex).trim();
                String valuePart = trimmed.substring(colonIndex + 1).trim();

                StringBuilder fullPathBuilder = new StringBuilder();
                for (PathEntry pe : pathStack) {
                    if (fullPathBuilder.length() > 0) {
                        fullPathBuilder.append(".");
                    }
                    fullPathBuilder.append(pe.key);
                }
                if (fullPathBuilder.length() > 0) {
                    fullPathBuilder.append(".");
                }
                fullPathBuilder.append(keyPart);
                String fullPath = fullPathBuilder.toString();

                boolean isSection = valuePart.isEmpty();
                boolean isList = false;
                if (isSection && i + 1 < defaultLines.size()) {
                    for (int j = i + 1; j < defaultLines.size() && j < i + 10; j++) {
                        String nextLine = defaultLines.get(j);
                        String nextTrimmed = nextLine.trim();
                        if (nextTrimmed.isEmpty() || nextLine.startsWith("#")) {
                            continue;
                        }
                        int nextIndent = nextLine.length() - nextTrimmed.length();
                        if (nextTrimmed.startsWith("-")) {
                            isList = true;
                            isSection = true;
                            break;
                        } else if (nextIndent > currentIndent) {
                            isSection = true;
                            break;
                        } else {
                            break;
                        }
                    }
                }

                if (isSection) {
                    if (containsPath(userMap, fullPath)) {
                        Object userValue = getAtPath(userMap, fullPath);
                        if (isList && userValue instanceof List<?> userList) {
                            merged.add(line);
                            for (Object item : userList) {
                                merged.add(" ".repeat(currentIndent + 2) + "- " + formatYamlValue(item));
                            }
                            while (i + 1 < defaultLines.size()) {
                                String nextLine = defaultLines.get(i + 1);
                                String nextTrimmed = nextLine.trim();
                                int nextIndent = nextLine.length() - nextTrimmed.length();
                                if (nextTrimmed.isEmpty() || nextLine.startsWith("#")) {
                                    if (nextIndent > currentIndent) {
                                        i++;
                                    } else {
                                        break;
                                    }
                                } else if (nextTrimmed.startsWith("-") && nextIndent > currentIndent) {
                                    i++;
                                } else {
                                    break;
                                }
                            }
                            pathStack.addLast(new PathEntry(keyPart, currentIndent));
                            continue;
                        } else {
                            merged.add(line);
                            pathStack.addLast(new PathEntry(keyPart, currentIndent));
                        }
                    } else {
                        merged.add(line);
                        pathStack.addLast(new PathEntry(keyPart, currentIndent));
                    }
                } else {
                    if (keyPart.equals(VERSION_KEY)) {
                        merged.add(line);
                    } else if (containsPath(userMap, fullPath)) {
                        Object userValue = getAtPath(userMap, fullPath);
                        String userValueStr = formatYamlValue(userValue);
                        String inlineComment = "";
                        int commentIndex = valuePart.indexOf('#');
                        if (commentIndex >= 0) {
                            inlineComment = " " + valuePart.substring(commentIndex);
                        }
                        merged.add(" ".repeat(currentIndent) + keyPart + ": " + userValueStr + inlineComment);
                    } else {
                        merged.add(line);
                    }
                }
            } else {
                merged.add(line);
            }
        }

        return merged;
    }

    private record PathEntry(String key, int indent) {}

    private static void updateConfigVersionLine(List<String> lines, int newVersion, List<String> defaultLines) {
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith(VERSION_KEY + ":") || trimmed.startsWith(VERSION_KEY + " ")) {
                int indent = line.length() - trimmed.length();
                String restOfLine = "";
                int colonIndex = trimmed.indexOf(':');
                if (colonIndex >= 0 && colonIndex + 1 < trimmed.length()) {
                    String afterColon = trimmed.substring(colonIndex + 1).trim();
                    int commentIndex = afterColon.indexOf('#');
                    if (commentIndex >= 0) {
                        restOfLine = " #" + afterColon.substring(commentIndex + 1);
                    }
                }
                lines.set(i, " ".repeat(indent) + VERSION_KEY + ": " + newVersion + restOfLine);
                found = true;
                break;
            }
        }
        if (!found) {
            String commentLine = "# config_version — used for migrations; match bundled defaults when updating.";
            for (int i = 0; i < defaultLines.size(); i++) {
                String line = defaultLines.get(i);
                String trimmed = line.trim();
                if (trimmed.startsWith(VERSION_KEY + ":") || trimmed.startsWith(VERSION_KEY + " ")) {
                    if (i > 0) {
                        String prev = defaultLines.get(i - 1);
                        if (prev.trim().startsWith("#")) {
                            commentLine = prev;
                        }
                    }
                    break;
                }
            }
            int insertIndex = 0;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !line.startsWith("#") && !trimmed.startsWith(VERSION_KEY)) {
                    insertIndex = i;
                    break;
                }
            }
            lines.add(insertIndex, commentLine);
            lines.add(insertIndex + 1, VERSION_KEY + ": " + newVersion);
            if (insertIndex + 2 < lines.size() && !lines.get(insertIndex + 2).trim().isEmpty()) {
                lines.add(insertIndex + 2, "");
            }
        }
    }

    private static String formatYamlValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "\"" + escapeForYamlDoubleQuotedString(s) + "\"";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return "[]";
            }
            if (list.size() == 1 && (list.get(0) instanceof String || list.get(0) instanceof Number)) {
                return "[" + formatYamlValue(list.get(0)) + "]";
            }
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(formatYamlValue(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        if (value instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                return "{}";
            }
            StringBuilder sb = new StringBuilder("{ ");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                String k = String.valueOf(e.getKey());
                if (k.matches("[A-Za-z0-9_-]+")) {
                    sb.append(k);
                } else {
                    sb.append('"').append(escapeForYamlDoubleQuotedString(k)).append('"');
                }
                sb.append(": ");
                sb.append(formatYamlValue(e.getValue()));
            }
            sb.append(" }");
            return sb.toString();
        }
        return value.toString();
    }

    private static String escapeForYamlDoubleQuotedString(String str) {
        StringBuilder sb = new StringBuilder(str.length() + 8);
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Index of the first line that starts a top-level YAML mapping key (column 0, {@code key:}). */
    private static int indexOfFirstTopLevelKeyLine(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (isTopLevelYamlKeyLine(lines.get(i))) {
                return i;
            }
        }
        return 0;
    }

    private static boolean isTopLevelYamlKeyLine(String line) {
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
}
