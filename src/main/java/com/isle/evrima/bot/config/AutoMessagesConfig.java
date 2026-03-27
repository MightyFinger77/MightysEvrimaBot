package com.isle.evrima.bot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Rotating in-game announcements via RCON {@code announce} (MightyTips-style). Optional {@code auto-messages.yml} beside {@code config.yml}.
 */
public final class AutoMessagesConfig {

    private static final Logger LOG = LoggerFactory.getLogger(AutoMessagesConfig.class);

    public static final AutoMessagesConfig DISABLED =
            new AutoMessagesConfig(false, 300, true, List.of(), null);

    private final boolean enabled;
    private final int intervalSeconds;
    /** When true, cycle messages in order; when false, shuffled round-robin without repeating until all used. */
    private final boolean sequential;
    private final List<String> messages;
    /** Absolute path of the YAML file, or null when {@link #DISABLED}. */
    private final Path sourcePath;

    public AutoMessagesConfig(
            boolean enabled,
            int intervalSeconds,
            boolean sequential,
            List<String> messages,
            Path sourcePath
    ) {
        this.enabled = enabled;
        this.intervalSeconds = intervalSeconds;
        this.sequential = sequential;
        this.messages = List.copyOf(messages);
        this.sourcePath = sourcePath;
    }

    public boolean enabled() {
        return enabled;
    }

    public int intervalSeconds() {
        return intervalSeconds;
    }

    public boolean sequential() {
        return sequential;
    }

    public List<String> messages() {
        return messages;
    }

    public Path sourcePath() {
        return sourcePath;
    }

    /**
     * Loads {@code auto-messages.yml} next to {@code config.yml}. Missing file → {@link #DISABLED}.
     */
    public static AutoMessagesConfig loadBesideConfig(Path configYaml) {
        Objects.requireNonNull(configYaml, "configYaml");
        Path parent = configYaml.getParent();
        if (parent == null) {
            parent = Path.of(".").toAbsolutePath().normalize();
        }
        Path yaml = parent.resolve("auto-messages.yml").normalize();
        if (!Files.isRegularFile(yaml)) {
            return DISABLED;
        }
        try (InputStream in = Files.newInputStream(yaml)) {
            Object raw = new Yaml().load(in);
            if (!(raw instanceof Map<?, ?> map)) {
                LOG.warn("auto-messages.yml: root must be a mapping — auto-messages disabled");
                return DISABLED;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) map;
            boolean en = parseBool(root.get("enabled"), false);
            int interval = (int) parseLong(root.get("interval_seconds"), 300L);
            if (interval < 10) {
                interval = 10;
            }
            if (interval > 604_800) {
                interval = 604_800;
            }
            String modeRaw = stringOrEmpty(root.get("mode")).toLowerCase(Locale.ROOT);
            if (modeRaw.isEmpty()) {
                modeRaw = stringOrEmpty(root.get("type")).toLowerCase(Locale.ROOT);
            }
            boolean sequential = true;
            if ("random".equals(modeRaw)) {
                sequential = false;
            } else if ("sequential".equals(modeRaw) || "sequentrial".equals(modeRaw)) {
                sequential = true;
            } else if (!modeRaw.isEmpty()) {
                LOG.warn("auto-messages.yml: unknown mode '{}' — using sequential", modeRaw);
            }
            List<String> msgs = parseMessages(root.get("messages"));
            if (msgs.isEmpty()) {
                msgs = List.of("Configure **messages** in `auto-messages.yml` beside your `config.yml`.");
            }
            return new AutoMessagesConfig(en, interval, sequential, msgs, yaml.toAbsolutePath().normalize());
        } catch (IOException e) {
            LOG.warn("auto-messages.yml load failed ({}): {}", yaml.toAbsolutePath(), e.toString());
            return DISABLED;
        }
    }

    private static List<String> parseMessages(Object v) {
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o == null) {
                    continue;
                }
                String s = o.toString().strip();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
            return out;
        }
        String s = v.toString().strip();
        return s.isEmpty() ? List.of() : List.of(s);
    }

    private static boolean parseBool(Object v, boolean def) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            String t = s.strip().toLowerCase(Locale.ROOT);
            if ("true".equals(t) || "yes".equals(t) || "1".equals(t)) {
                return true;
            }
            if ("false".equals(t) || "no".equals(t) || "0".equals(t)) {
                return false;
            }
        }
        return def;
    }

    private static long parseLong(Object v, long def) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static String stringOrEmpty(Object v) {
        return v == null ? "" : v.toString().strip();
    }
}
