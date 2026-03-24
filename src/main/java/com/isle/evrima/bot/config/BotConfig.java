package com.isle.evrima.bot.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
    /** Empty = disabled — text channel topics updated with the same status line (see {@link #serverStatusTopicChannelIds()}). */
    private final List<Long> serverStatusTopicChannelIds;
    private final int serverStatusTopicIntervalMinutes;
    /** 0 = omit “/max” in topic (show online count only). */
    private final int serverStatusTopicMaxPlayers;
    /** Empty = JVM default zone (e.g. host OS). */
    private final String serverStatusTopicTimezoneId;
    private final boolean serverStatusTopicShowUniqueSeen;
    /** If true, append bot/JVM uptime (not the same as dedicated server uptime). */
    private final boolean serverStatusTopicShowBridgeUptime;
    /**
     * After each {@code setTopic} completes, wait this many seconds before PATCHing the next channel when
     * {@link #serverStatusTopicChannelIds()} has 2+ ids. Discord’s shared guild bucket often returns Retry-After ~180s.
     */
    private final int serverStatusTopicMultiChannelStaggerSeconds;
    /** Periodically set RCON {@code aidensity} from online % of {@link #adaptiveAiDensityMaxPlayers()}. */
    private final boolean adaptiveAiDensityEnabled;
    private final int adaptiveAiDensityIntervalMinutes;
    /** Slot cap used only for fill %; required when adaptive AI density is enabled. */
    private final int adaptiveAiDensityMaxPlayers;
    private final List<AdaptiveAiDensityTier> adaptiveAiDensityTiers;
    /** 0 = disabled */
    private final int scheduledWipecorpsesIntervalMinutes;
    /** 0 = no in-game announce before wipe */
    private final int scheduledWipecorpsesWarnBeforeMinutes;
    private final String scheduledWipecorpsesAnnounceMessage;
    /** 0 = disabled */
    private final long ingameChatLogChannelId;
    /** Empty = disabled (even if channel_id set) */
    private final String ingameChatLogPathRaw;
    private final int ingameChatLogPollSeconds;
    /** When false, {@code [Spatial]} / {@code [Local]} chat lines are dropped after parse (global-only mirror). */
    private final boolean ingameChatLogMirrorLocalChat;
    /** Lines containing any of these substrings are mirrored (e.g. chat + kill/death markers). */
    private final List<String> ingameChatLogLineContainsAny;

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
            List<Long> serverStatusTopicChannelIds,
            int serverStatusTopicIntervalMinutes,
            int serverStatusTopicMaxPlayers,
            String serverStatusTopicTimezoneId,
            boolean serverStatusTopicShowUniqueSeen,
            boolean serverStatusTopicShowBridgeUptime,
            int serverStatusTopicMultiChannelStaggerSeconds,
            boolean adaptiveAiDensityEnabled,
            int adaptiveAiDensityIntervalMinutes,
            int adaptiveAiDensityMaxPlayers,
            List<AdaptiveAiDensityTier> adaptiveAiDensityTiers,
            int scheduledWipecorpsesIntervalMinutes,
            int scheduledWipecorpsesWarnBeforeMinutes,
            String scheduledWipecorpsesAnnounceMessage,
            long ingameChatLogChannelId,
            String ingameChatLogPathRaw,
            int ingameChatLogPollSeconds,
            boolean ingameChatLogMirrorLocalChat,
            List<String> ingameChatLogLineContainsAny
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
        this.serverStatusTopicChannelIds = List.copyOf(serverStatusTopicChannelIds);
        this.serverStatusTopicIntervalMinutes = serverStatusTopicIntervalMinutes;
        this.serverStatusTopicMaxPlayers = serverStatusTopicMaxPlayers;
        this.serverStatusTopicTimezoneId = serverStatusTopicTimezoneId;
        this.serverStatusTopicShowUniqueSeen = serverStatusTopicShowUniqueSeen;
        this.serverStatusTopicShowBridgeUptime = serverStatusTopicShowBridgeUptime;
        this.serverStatusTopicMultiChannelStaggerSeconds = serverStatusTopicMultiChannelStaggerSeconds;
        this.adaptiveAiDensityEnabled = adaptiveAiDensityEnabled;
        this.adaptiveAiDensityIntervalMinutes = adaptiveAiDensityIntervalMinutes;
        this.adaptiveAiDensityMaxPlayers = adaptiveAiDensityMaxPlayers;
        this.adaptiveAiDensityTiers = List.copyOf(adaptiveAiDensityTiers);
        this.scheduledWipecorpsesIntervalMinutes = scheduledWipecorpsesIntervalMinutes;
        this.scheduledWipecorpsesWarnBeforeMinutes = scheduledWipecorpsesWarnBeforeMinutes;
        this.scheduledWipecorpsesAnnounceMessage = scheduledWipecorpsesAnnounceMessage;
        this.ingameChatLogChannelId = ingameChatLogChannelId;
        this.ingameChatLogPathRaw = ingameChatLogPathRaw;
        this.ingameChatLogPollSeconds = ingameChatLogPollSeconds;
        this.ingameChatLogMirrorLocalChat = ingameChatLogMirrorLocalChat;
        this.ingameChatLogLineContainsAny = List.copyOf(ingameChatLogLineContainsAny);
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

        Map<String, Object> statusTopic = mapOrEmpty(root.get("server_status_topic"));
        List<Long> stChannels = parseServerStatusTopicChannelIds(statusTopic);
        int stInterval = (int) parseLong(statusTopic.get("interval_minutes"), 5L);
        if (stInterval < 1) {
            stInterval = 1;
        }
        int stMaxPlayers = (int) parseLong(statusTopic.get("max_players"), 0L);
        if (stMaxPlayers < 0) {
            stMaxPlayers = 0;
        }
        String stTz = stringOrEmpty(statusTopic.get("timezone"));
        boolean stUnique = parseBooleanYaml(statusTopic.get("show_unique_seen"), true);
        boolean stBridgeUp = parseBooleanYaml(statusTopic.get("show_bridge_uptime"), false);
        int stStagger = (int) parseLong(statusTopic.get("multi_channel_stagger_seconds"), 210L);
        if (stStagger < 120) {
            stStagger = 120;
        }
        if (stStagger > 900) {
            stStagger = 900;
        }

        Map<String, Object> adaptiveAi = mapOrEmpty(root.get("adaptive_ai_density"));
        boolean aaEnabled = parseBooleanYaml(adaptiveAi.get("enabled"), false);
        int aaInterval = (int) parseLong(adaptiveAi.get("interval_minutes"), 4L);
        if (aaInterval < 1) {
            aaInterval = 1;
        }
        int aaMaxPlayers = (int) parseLong(adaptiveAi.get("max_players"), 0L);
        if (aaMaxPlayers < 0) {
            aaMaxPlayers = 0;
        }
        List<AdaptiveAiDensityTier> aaTiers = parseAdaptiveAiDensityTiers(adaptiveAi.get("tiers"));
        if (aaEnabled && aaMaxPlayers <= 0) {
            throw new IOException("adaptive_ai_density.enabled is true but max_players must be > 0 (server slot cap for fill %).");
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

        Map<String, Object> chatLog = mapOrEmpty(root.get("ingame_chat_log"));
        long chatCh = parseLong(chatLog.get("channel_id"), 0L);
        String chatPath = stringOrEmpty(chatLog.get("path"));
        int chatPoll = (int) parseLong(chatLog.get("poll_seconds"), 2L);
        if (chatPoll < 1) {
            chatPoll = 1;
        }
        if (chatPoll > 60) {
            chatPoll = 60;
        }
        List<String> chatMarkers = parseLogLineMarkers(chatLog.get("line_contains"));
        boolean chatMirrorLocal = parseBooleanYaml(chatLog.get("mirror_local_chat"), false);

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
                stChannels,
                stInterval,
                stMaxPlayers,
                stTz,
                stUnique,
                stBridgeUp,
                stStagger,
                aaEnabled,
                aaInterval,
                aaMaxPlayers,
                aaTiers,
                wipeMin,
                warnBefore,
                wipeAnnounce,
                chatCh,
                chatPath,
                chatPoll,
                chatMirrorLocal,
                chatMarkers
        );
    }

    /**
     * {@code line_contains} may be one string or a YAML list. Empty / missing defaults to chat + kill markers
     * (same markers as common The Isle log-to-Discord tools).
     */
    private static List<String> parseLogLineMarkers(Object v) {
        if (v == null) {
            return defaultLogLineMarkers();
        }
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                String s = stringOrEmpty(o);
                if (!s.isBlank()) {
                    out.add(s);
                }
            }
            return out.isEmpty() ? defaultLogLineMarkers() : List.copyOf(out);
        }
        String single = stringOrEmpty(v);
        if (single.isBlank()) {
            return defaultLogLineMarkers();
        }
        return List.of(single);
    }

    private static List<String> defaultLogLineMarkers() {
        return List.of("LogTheIsleChatData", "LogTheIsleKillData");
    }

    /**
     * {@code channel_id} (single) and/or {@code channel_ids} (list); merged, order preserved, duplicates removed.
     */
    private static List<Long> parseServerStatusTopicChannelIds(Map<String, Object> statusTopic) {
        LinkedHashSet<Long> set = new LinkedHashSet<>();
        long one = parseLong(statusTopic.get("channel_id"), 0L);
        if (one != 0L) {
            set.add(one);
        }
        Object listObj = statusTopic.get("channel_ids");
        if (listObj instanceof List<?> list) {
            for (Object o : list) {
                long id = parseLong(o, 0L);
                if (id != 0L) {
                    set.add(id);
                }
            }
        }
        return List.copyOf(set);
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

    private static boolean parseBooleanYaml(Object v, boolean defaultVal) {
        if (v == null) {
            return defaultVal;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return defaultVal;
        }
        return Boolean.parseBoolean(s);
    }

    private static double parseDoubleYaml(Object v, double defaultVal) {
        if (v == null) {
            return defaultVal;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<AdaptiveAiDensityTier> parseAdaptiveAiDensityTiers(Object tiersObj) throws IOException {
        if (!(tiersObj instanceof List<?> list) || list.isEmpty()) {
            return AdaptiveAiDensityTier.defaultTiers();
        }
        List<AdaptiveAiDensityTier> out = new ArrayList<>();
        int idx = 0;
        for (Object row : list) {
            idx++;
            if (!(row instanceof Map)) {
                throw new IOException("adaptive_ai_density.tiers[" + idx + "] must be a map (min_percent, max_percent, density)");
            }
            Map<String, Object> map = (Map<String, Object>) row;
            int min = (int) parseLong(map.get("min_percent"), -1L);
            int max = (int) parseLong(map.get("max_percent"), -1L);
            double density = parseDoubleYaml(map.get("density"), Double.NaN);
            if (min < 0 || max < 0 || min > 100 || max > 100 || min > max
                    || Double.isNaN(density) || density < 0 || Double.isInfinite(density)) {
                throw new IOException("adaptive_ai_density.tiers[" + idx + "] invalid (need 0<=min<=max<=100, density>=0): " + row);
            }
            out.add(new AdaptiveAiDensityTier(min, max, density));
        }
        return AdaptiveAiDensityTier.sortedCopy(out);
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
     * Text channels whose topics receive the same RCON status line. Empty disables.
     * YAML: {@code channel_id} (one id) and/or {@code channel_ids} (list); both may be set and are merged.
     */
    public List<Long> serverStatusTopicChannelIds() {
        return serverStatusTopicChannelIds;
    }

    public int serverStatusTopicIntervalMinutes() {
        return serverStatusTopicIntervalMinutes;
    }

    /** Server slot cap for “N/max” in the topic; {@code 0} shows online count only. */
    public int serverStatusTopicMaxPlayers() {
        return serverStatusTopicMaxPlayers;
    }

    /** IANA timezone id (e.g. {@code America/Chicago}) for “Last update”; empty = default zone. */
    public String serverStatusTopicTimezoneId() {
        return serverStatusTopicTimezoneId;
    }

    public boolean serverStatusTopicShowUniqueSeen() {
        return serverStatusTopicShowUniqueSeen;
    }

    public boolean serverStatusTopicShowBridgeUptime() {
        return serverStatusTopicShowBridgeUptime;
    }

    /**
     * Delay between sequential topic updates per channel when multiple {@code server_status_topic} channels are set.
     * Default 210s — above common Discord Retry-After (~183s) for {@code PATCH /channels}.
     */
    public int serverStatusTopicMultiChannelStaggerSeconds() {
        return serverStatusTopicMultiChannelStaggerSeconds;
    }

    public boolean adaptiveAiDensityEnabled() {
        return adaptiveAiDensityEnabled;
    }

    public int adaptiveAiDensityIntervalMinutes() {
        return adaptiveAiDensityIntervalMinutes;
    }

    /** Server slot cap for population fill % (must be &gt; 0 when feature is enabled). */
    public int adaptiveAiDensityMaxPlayers() {
        return adaptiveAiDensityMaxPlayers;
    }

    /** Sorted by {@code min_percent}. */
    public List<AdaptiveAiDensityTier> adaptiveAiDensityTiers() {
        return adaptiveAiDensityTiers;
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

    /** Discord channel for mirrored in-game log lines; {@code 0} disables. */
    public long ingameChatLogChannelId() {
        return ingameChatLogChannelId;
    }

    /**
     * Absolute or relative path to the dedicated server log (e.g. {@code TheIsle.log}).
     * Empty disables mirroring even if {@link #ingameChatLogChannelId()} is set.
     */
    public Path ingameChatLogPath() {
        if (ingameChatLogPathRaw.isBlank()) {
            return null;
        }
        return Path.of(ingameChatLogPathRaw);
    }

    /** How often to poll the log file (1–60 seconds). */
    public int ingameChatLogPollSeconds() {
        return ingameChatLogPollSeconds;
    }

    /**
     * If true, proximity lines ({@code [Spatial]}, {@code [Local]}) are mirrored like global chat.
     * If false (default), they are filtered out after parse.
     */
    public boolean ingameChatLogMirrorLocalChat() {
        return ingameChatLogMirrorLocalChat;
    }

    /**
     * Log lines containing <b>any</b> of these substrings are posted. Defaults include chat and kill/death
     * ({@code LogTheIsleChatData}, {@code LogTheIsleKillData}). Add more (e.g. a hunger tag from your log) in config.
     */
    public List<String> ingameChatLogLineContainsAny() {
        return ingameChatLogLineContainsAny;
    }
}
