package com.isle.evrima.bot.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class BotConfig {

    private final String discordToken;
    private final long guildId;
    private final List<Long> moderatorRoleIds;
    private final List<Long> adminRoleIds;
    private final List<Long> headAdminRoleIds;
    private final String rconHost;
    private final int rconPort;
    private final String rconPassword;
    private final int rconTimeoutMs;
    private final Path databasePath;
    private final int linkCodeTtlMinutes;
    private final int dailySpinMin;
    private final int dailySpinMax;
    private final String ecosystemTitle;
    private final int ecosystemCacheTtlSeconds;
    private final String ecosystemTaxonomyRelative;
    private final long populationDashboardChannelId;
    private final int populationDashboardIntervalMinutes;
    /** 0 = disabled */
    private final int scheduledWipecorpsesIntervalMinutes;
    /** 0 = no in-game announce before wipe */
    private final int scheduledWipecorpsesWarnBeforeMinutes;
    private final String scheduledWipecorpsesAnnounceMessage;

    public BotConfig(
            String discordToken,
            long guildId,
            List<Long> moderatorRoleIds,
            List<Long> adminRoleIds,
            List<Long> headAdminRoleIds,
            String rconHost,
            int rconPort,
            String rconPassword,
            int rconTimeoutMs,
            Path databasePath,
            int linkCodeTtlMinutes,
            int dailySpinMin,
            int dailySpinMax,
            String ecosystemTitle,
            int ecosystemCacheTtlSeconds,
            String ecosystemTaxonomyRelative,
            long populationDashboardChannelId,
            int populationDashboardIntervalMinutes,
            int scheduledWipecorpsesIntervalMinutes,
            int scheduledWipecorpsesWarnBeforeMinutes,
            String scheduledWipecorpsesAnnounceMessage
    ) {
        this.discordToken = discordToken;
        this.guildId = guildId;
        this.moderatorRoleIds = List.copyOf(moderatorRoleIds);
        this.adminRoleIds = List.copyOf(adminRoleIds);
        this.headAdminRoleIds = List.copyOf(headAdminRoleIds);
        this.rconHost = rconHost;
        this.rconPort = rconPort;
        this.rconPassword = rconPassword;
        this.rconTimeoutMs = rconTimeoutMs;
        this.databasePath = databasePath;
        this.linkCodeTtlMinutes = linkCodeTtlMinutes;
        this.dailySpinMin = dailySpinMin;
        this.dailySpinMax = dailySpinMax;
        this.ecosystemTitle = ecosystemTitle;
        this.ecosystemCacheTtlSeconds = ecosystemCacheTtlSeconds;
        this.ecosystemTaxonomyRelative = ecosystemTaxonomyRelative;
        this.populationDashboardChannelId = populationDashboardChannelId;
        this.populationDashboardIntervalMinutes = populationDashboardIntervalMinutes;
        this.scheduledWipecorpsesIntervalMinutes = scheduledWipecorpsesIntervalMinutes;
        this.scheduledWipecorpsesWarnBeforeMinutes = scheduledWipecorpsesWarnBeforeMinutes;
        this.scheduledWipecorpsesAnnounceMessage = scheduledWipecorpsesAnnounceMessage;
    }

    @SuppressWarnings("unchecked")
    public static BotConfig load(Path yamlFile) throws IOException {
        Objects.requireNonNull(yamlFile, "yamlFile");
        if (!Files.isRegularFile(yamlFile)) {
            throw new IOException("Missing config file: " + yamlFile.toAbsolutePath() + " (copy config.example.yml to config.yml)");
        }
        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(yamlFile)) {
            root = new Yaml().load(in);
        }
        if (root == null) {
            throw new IOException("Empty config: " + yamlFile);
        }

        Map<String, Object> discord = requireMap(root, "discord");
        String envToken = System.getenv("DISCORD_TOKEN");
        String token = envToken != null && !envToken.isBlank()
                ? envToken.trim()
                : stringOrEmpty(discord.get("token"));
        if (token.isBlank() || "paste-bot-token-or-use-env".equals(token)) {
            throw new IOException("Set discord.token in config.yml or DISCORD_TOKEN in the environment.");
        }

        long guildId = parseLong(discord.get("guild_id"), 0L);

        Map<String, Object> roles = mapOrEmpty(discord.get("roles"));
        List<Long> modRoles = parseIdList(roles.get("moderator"));
        List<Long> adminRoles = parseIdList(roles.get("admin"));
        List<Long> headRoles = parseIdList(roles.get("head_admin"));

        Map<String, Object> rcon = requireMap(root, "rcon");
        String rconHost = stringOrEmpty(rcon.get("host"));
        if (rconHost.isBlank()) {
            rconHost = "127.0.0.1";
        }
        int rconPort = (int) parseLong(rcon.get("port"), 5555L);
        String rconPassword = stringOrEmpty(rcon.get("password"));
        if (rconPassword.isBlank()) {
            throw new IOException("rcon.password is required.");
        }
        int rconTimeout = (int) parseLong(rcon.get("timeout_ms"), 30000L);

        Map<String, Object> db = requireMap(root, "database");
        Path dbPath = Path.of(stringOrEmpty(db.get("path")).isBlank() ? "./data/evrima-bot.sqlite" : stringOrEmpty(db.get("path")));

        Map<String, Object> linking = mapOrEmpty(root.get("linking"));
        int codeTtl = (int) parseLong(linking.get("code_ttl_minutes"), 20L);

        Map<String, Object> eco = mapOrEmpty(root.get("economy"));
        int spinMin = (int) parseLong(eco.get("daily_spin_min"), 10L);
        int spinMax = (int) parseLong(eco.get("daily_spin_max"), 250L);

        Map<String, Object> ecosystem = mapOrEmpty(root.get("ecosystem"));
        String ecoTitle = stringOrEmpty(ecosystem.get("title"));
        int ecoCache = (int) parseLong(ecosystem.get("cache_ttl_seconds"), 60L);
        if (ecoCache < 5) {
            ecoCache = 5;
        }
        String ecoTaxonomy = stringOrEmpty(ecosystem.get("taxonomy_path"));

        Map<String, Object> popDash = mapOrEmpty(root.get("population_dashboard"));
        long popChannel = parseLong(popDash.get("channel_id"), 0L);
        int popInterval = (int) parseLong(popDash.get("interval_minutes"), 5L);
        if (popInterval < 1) {
            popInterval = 1;
        }

        Map<String, Object> schedWipe = mapOrEmpty(root.get("scheduled_wipecorpses"));
        int wipeMin = (int) parseLong(schedWipe.get("interval_minutes"), 0L);
        if (wipeMin < 0) {
            wipeMin = 0;
        }
        int warnBefore = (int) parseLong(schedWipe.get("warn_before_minutes"), 5L);
        if (warnBefore < 0) {
            warnBefore = 0;
        }
        String wipeAnnounce = stringOrEmpty(schedWipe.get("announce_message"));
        if (wipeAnnounce.isBlank()) {
            wipeAnnounce = "5 minutes until corpse wipe, eat up!";
        }

        return new BotConfig(
                token,
                guildId,
                modRoles,
                adminRoles,
                headRoles,
                rconHost,
                rconPort,
                rconPassword,
                rconTimeout,
                dbPath,
                codeTtl,
                spinMin,
                spinMax,
                ecoTitle,
                ecoCache,
                ecoTaxonomy,
                popChannel,
                popInterval,
                wipeMin,
                warnBefore,
                wipeAnnounce
        );
    }

    private static Map<String, Object> requireMap(Map<String, Object> root, String key) throws IOException {
        Object v = root.get(key);
        if (!(v instanceof Map)) {
            throw new IOException("config." + key + " must be a map");
        }
        return (Map<String, Object>) v;
    }

    private static Map<String, Object> mapOrEmpty(Object v) {
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        return Map.of();
    }

    private static String stringOrEmpty(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static long parseLong(Object v, long defaultVal) {
        if (v == null) {
            return defaultVal;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static List<Long> parseIdList(Object v) {
        List<Long> out = new ArrayList<>();
        if (v == null) {
            return out;
        }
        if (v instanceof List<?> list) {
            for (Object o : list) {
                long id = parseLong(o, Long.MIN_VALUE);
                if (id != Long.MIN_VALUE) {
                    out.add(id);
                }
            }
            return out;
        }
        long single = parseLong(v, Long.MIN_VALUE);
        if (single != Long.MIN_VALUE) {
            out.add(single);
        }
        return out;
    }

    public String discordToken() {
        return discordToken;
    }

    public long guildId() {
        return guildId;
    }

    public List<Long> moderatorRoleIds() {
        return moderatorRoleIds;
    }

    public List<Long> adminRoleIds() {
        return adminRoleIds;
    }

    public List<Long> headAdminRoleIds() {
        return headAdminRoleIds;
    }

    public String rconHost() {
        return rconHost;
    }

    public int rconPort() {
        return rconPort;
    }

    public String rconPassword() {
        return rconPassword;
    }

    public int rconTimeoutMs() {
        return rconTimeoutMs;
    }

    public Path databasePath() {
        return databasePath;
    }

    public int linkCodeTtlMinutes() {
        return linkCodeTtlMinutes;
    }

    public int dailySpinMin() {
        return dailySpinMin;
    }

    public int dailySpinMax() {
        return dailySpinMax;
    }

    public String ecosystemTitle() {
        return ecosystemTitle;
    }

    public int ecosystemCacheTtlSeconds() {
        return ecosystemCacheTtlSeconds;
    }

    public String ecosystemTaxonomyRelative() {
        return ecosystemTaxonomyRelative;
    }

    public long populationDashboardChannelId() {
        return populationDashboardChannelId;
    }

    public int populationDashboardIntervalMinutes() {
        return populationDashboardIntervalMinutes;
    }

    /**
     * Minutes between automatic RCON {@code wipecorpses}; {@code 0} disables the scheduler.
     */
    public int scheduledWipecorpsesIntervalMinutes() {
        return scheduledWipecorpsesIntervalMinutes;
    }

    /**
     * In-game RCON {@code announce} this many minutes before {@code wipecorpses}; {@code 0} skips the warning.
     */
    public int scheduledWipecorpsesWarnBeforeMinutes() {
        return scheduledWipecorpsesWarnBeforeMinutes;
    }

    public String scheduledWipecorpsesAnnounceMessage() {
        return scheduledWipecorpsesAnnounceMessage;
    }
}
