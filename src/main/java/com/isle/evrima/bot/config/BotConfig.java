package com.isle.evrima.bot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOG = LoggerFactory.getLogger(BotConfig.class);

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
    /** Absolute path to the loaded {@code config.yml} (for admin persistence writes). */
    private final Path configYamlPath;
    private final int linkCodeTtlMinutes;
    private final int dailySpinMin;
    private final int dailySpinMax;
    private final String ecosystemTitle;
    private final int ecosystemCacheTtlSeconds;
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
    /** Dynamically remove/add species from playables list based on live counts. */
    private final boolean speciesPopulationControlEnabled;
    /** Tick interval for species-cap checks (seconds). */
    private final int speciesPopulationControlIntervalSeconds;
    /**
     * Unlock when count is {@code <= cap - unlock_below_offset}. Example cap 15 + offset 1 unlocks at 14 or lower.
     * This hysteresis avoids lock/unlock flapping at the exact cap boundary.
     */
    private final int speciesPopulationControlUnlockBelowOffset;
    /** Optional in-game announce on lock/unlock events (can be spammy on busy servers). */
    private final boolean speciesPopulationControlAnnounceChanges;
    /** Species display name -> cap. 0 means unmanaged/unlimited. */
    private final Map<String, Integer> speciesPopulationCaps;
    /** Runtime default for scheduled corpse wipes: {@code true}, {@code false}, or {@code dynamic}. */
    private final String scheduledWipecorpsesEnabledMode;
    /** Used only when enabled mode is {@code dynamic}. */
    private final int scheduledWipecorpsesDynamicMaxPlayers;
    /** Used only when enabled mode is {@code dynamic}. */
    private final int scheduledWipecorpsesDynamicEnablePercent;
    /** Used only when enabled mode is {@code dynamic}; avoids rapid off/on flapping near threshold. */
    private final int scheduledWipecorpsesDynamicDisableGraceSeconds;
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
    /** When true and {@link #killFlavorPack()} has content, kill lines use {@code kill-flavor.yml} templates. */
    private final boolean ingameChatLogKillFlavorEnabled;
    /** Absolute path used to load {@link #killFlavorPack()} (for logging / troubleshooting). */
    private final Path killFlavorYamlPath;
    private final KillFlavorPack killFlavorPack;

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
            boolean speciesPopulationControlEnabled,
            int speciesPopulationControlIntervalSeconds,
            int speciesPopulationControlUnlockBelowOffset,
            boolean speciesPopulationControlAnnounceChanges,
            Map<String, Integer> speciesPopulationCaps,
            String scheduledWipecorpsesEnabledMode,
            int scheduledWipecorpsesDynamicMaxPlayers,
            int scheduledWipecorpsesDynamicEnablePercent,
            int scheduledWipecorpsesDynamicDisableGraceSeconds,
            int scheduledWipecorpsesIntervalMinutes,
            int scheduledWipecorpsesWarnBeforeMinutes,
            String scheduledWipecorpsesAnnounceMessage,
            long ingameChatLogChannelId,
            String ingameChatLogPathRaw,
            int ingameChatLogPollSeconds,
            boolean ingameChatLogMirrorLocalChat,
            List<String> ingameChatLogLineContainsAny,
            boolean ingameChatLogKillFlavorEnabled,
            Path killFlavorYamlPath,
            KillFlavorPack killFlavorPack,
            Path configYamlPath
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
        this.configYamlPath = Objects.requireNonNull(configYamlPath, "configYamlPath");
        this.linkCodeTtlMinutes = linkCodeTtlMinutes;
        this.dailySpinMin = dailySpinMin;
        this.dailySpinMax = dailySpinMax;
        this.ecosystemTitle = ecosystemTitle;
        this.ecosystemCacheTtlSeconds = ecosystemCacheTtlSeconds;
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
        this.speciesPopulationControlEnabled = speciesPopulationControlEnabled;
        this.speciesPopulationControlIntervalSeconds = speciesPopulationControlIntervalSeconds;
        this.speciesPopulationControlUnlockBelowOffset = speciesPopulationControlUnlockBelowOffset;
        this.speciesPopulationControlAnnounceChanges = speciesPopulationControlAnnounceChanges;
        this.speciesPopulationCaps = Map.copyOf(speciesPopulationCaps);
        this.scheduledWipecorpsesEnabledMode = scheduledWipecorpsesEnabledMode;
        this.scheduledWipecorpsesDynamicMaxPlayers = scheduledWipecorpsesDynamicMaxPlayers;
        this.scheduledWipecorpsesDynamicEnablePercent = scheduledWipecorpsesDynamicEnablePercent;
        this.scheduledWipecorpsesDynamicDisableGraceSeconds = scheduledWipecorpsesDynamicDisableGraceSeconds;
        this.scheduledWipecorpsesIntervalMinutes = scheduledWipecorpsesIntervalMinutes;
        this.scheduledWipecorpsesWarnBeforeMinutes = scheduledWipecorpsesWarnBeforeMinutes;
        this.scheduledWipecorpsesAnnounceMessage = scheduledWipecorpsesAnnounceMessage;
        this.ingameChatLogChannelId = ingameChatLogChannelId;
        this.ingameChatLogPathRaw = ingameChatLogPathRaw;
        this.ingameChatLogPollSeconds = ingameChatLogPollSeconds;
        this.ingameChatLogMirrorLocalChat = ingameChatLogMirrorLocalChat;
        this.ingameChatLogLineContainsAny = List.copyOf(ingameChatLogLineContainsAny);
        this.ingameChatLogKillFlavorEnabled = ingameChatLogKillFlavorEnabled;
        this.killFlavorYamlPath = killFlavorYamlPath == null
                ? Path.of("kill-flavor.yml")
                : killFlavorYamlPath.toAbsolutePath().normalize();
        this.killFlavorPack = Objects.requireNonNull(killFlavorPack, "killFlavorPack");
    }

    @SuppressWarnings("unchecked")
    public static BotConfig load(Path yamlFile) throws IOException {
        Objects.requireNonNull(yamlFile, "yamlFile");
        if (!Files.isRegularFile(yamlFile)) {
            throw new IOException("Missing config file: " + yamlFile.toAbsolutePath()
                    + " (run with no args to auto-create configs/ or use config/config.yml, or pass a path to your config.yml)");
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

        Map<String, Object> popDash = mapOrEmpty(root.get("population_dashboard"));
        long popChannel = parseLong(popDash.get("channel_id"), 0L);
        int popInterval = (int) parseLong(popDash.get("interval_minutes"), 3L);
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
        int aaInterval = (int) parseLong(adaptiveAi.get("interval_minutes"), 3L);
        if (aaInterval < 1) {
            aaInterval = 1;
        }
        int aaMaxPlayers = (int) parseLong(adaptiveAi.get("max_players"), 140L);
        if (aaMaxPlayers < 0) {
            aaMaxPlayers = 0;
        }
        List<AdaptiveAiDensityTier> aaTiers = parseAdaptiveAiDensityTiers(adaptiveAi.get("tiers"));
        if (aaEnabled && aaMaxPlayers <= 0) {
            throw new IOException("adaptive_ai_density.enabled is true but max_players must be > 0 (server slot cap for fill %).");
        }

        Map<String, Object> speciesControl = mapOrEmpty(root.get("species_population_control"));
        boolean spEnabled = parseBooleanYaml(speciesControl.get("enabled"), false);
        int spIntervalSec = (int) parseLong(speciesControl.get("interval_seconds"), 60L);
        if (spIntervalSec < 10) {
            spIntervalSec = 10;
        }
        if (spIntervalSec > 600) {
            spIntervalSec = 600;
        }
        int spUnlockOffset = (int) parseLong(speciesControl.get("unlock_below_offset"), 1L);
        if (spUnlockOffset < 0) {
            spUnlockOffset = 0;
        }
        if (spUnlockOffset > 20) {
            spUnlockOffset = 20;
        }
        boolean spAnnounce = parseBooleanYaml(speciesControl.get("announce_changes"), false);
        Map<String, Integer> spCaps = parseSpeciesPopulationCaps(speciesControl.get("caps"));
        if (spEnabled && spCaps.isEmpty()) {
            throw new IOException("species_population_control.enabled is true but caps is empty (set at least one species cap > 0).");
        }

        Map<String, Object> schedWipe = mapOrEmpty(root.get("scheduled_wipecorpses"));
        int wipeMin = (int) parseLong(schedWipe.get("interval_minutes"), 180L);
        if (wipeMin < 0) {
            wipeMin = 0;
        }
        String wipeEnabledMode = parseModeOnOffDynamic(schedWipe.get("enabled"), "dynamic");
        int wipeDynMaxPlayers = (int) parseLong(schedWipe.get("dynamic_max_players"), 145L);
        if (wipeDynMaxPlayers < 0) {
            wipeDynMaxPlayers = 0;
        }
        int wipeDynPct = (int) parseLong(schedWipe.get("dynamic_enable_percent"), 70L);
        if (wipeDynPct < 0) {
            wipeDynPct = 0;
        }
        if (wipeDynPct > 100) {
            wipeDynPct = 100;
        }
        int wipeDynGraceSec = (int) parseLong(schedWipe.get("dynamic_disable_grace_seconds"), 120L);
        if (wipeDynGraceSec < 0) {
            wipeDynGraceSec = 0;
        }
        if (wipeDynGraceSec > 600) {
            wipeDynGraceSec = 600;
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
        boolean killFlavorEnabled = parseBooleanYaml(chatLog.get("kill_flavor_enabled"), true);
        String killFlavorPathRaw = stringOrEmpty(chatLog.get("kill_flavor_path"));
        Path killFlavorYaml = resolveBesideConfig(yamlFile, killFlavorPathRaw, "kill-flavor.yml");
        KillFlavorPack killPack = KillFlavorPack.EMPTY;
        if (killFlavorEnabled) {
            try {
                killPack = KillFlavorPack.loadFromPath(killFlavorYaml);
                if (!killPack.hasAny()) {
                    LOG.warn("ingame_chat_log.kill_flavor_enabled but {} has no usable quips — factual kill lines until you add some",
                            killFlavorYaml.toAbsolutePath());
                }
            } catch (IOException e) {
                LOG.warn("ingame_chat_log.kill_flavor load failed ({}): {}", killFlavorYaml.toAbsolutePath(), e.toString());
                killPack = KillFlavorPack.EMPTY;
            }
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
                spEnabled,
                spIntervalSec,
                spUnlockOffset,
                spAnnounce,
                spCaps,
                wipeEnabledMode,
                wipeDynMaxPlayers,
                wipeDynPct,
                wipeDynGraceSec,
                wipeMin,
                warnBefore,
                wipeAnnounce,
                chatCh,
                chatPath,
                chatPoll,
                chatMirrorLocal,
                chatMarkers,
                killFlavorEnabled,
                killFlavorYaml,
                killPack,
                yamlFile.toAbsolutePath().normalize()
        );
    }

    private static Path resolveBesideConfig(Path configYaml, String pathRaw, String defaultFilename) {
        Path parent = configYaml.getParent();
        if (parent == null) {
            parent = Path.of(".").toAbsolutePath().normalize();
        }
        String use = pathRaw.isBlank() ? defaultFilename : pathRaw.trim();
        return parent.resolve(use).normalize();
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

    private static String parseModeOnOffDynamic(Object v, String defaultMode) {
        if (v == null) {
            return defaultMode;
        }
        if (v instanceof Boolean b) {
            return b ? "true" : "false";
        }
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty()) {
            return defaultMode;
        }
        if ("dynamic".equals(s)) {
            return "dynamic";
        }
        if ("true".equals(s) || "on".equals(s) || "enable".equals(s) || "enabled".equals(s)) {
            return "true";
        }
        if ("false".equals(s) || "off".equals(s) || "disable".equals(s) || "disabled".equals(s)) {
            return "false";
        }
        return defaultMode;
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

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> parseSpeciesPopulationCaps(Object capsObj) throws IOException {
        if (!(capsObj instanceof Map<?, ?> raw) || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            String name = stringOrEmpty(e.getKey());
            if (name.isBlank()) {
                continue;
            }
            int cap = (int) parseLong(e.getValue(), -1L);
            if (cap < 0) {
                throw new IOException("species_population_control.caps entry '" + name + "' is invalid (cap must be >= 0)");
            }
            if (cap == 0) {
                continue;
            }
            out.put(name, cap);
        }
        return Map.copyOf(out);
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

    public Path configYamlPath() {
        return configYamlPath;
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

    public boolean speciesPopulationControlEnabled() {
        return speciesPopulationControlEnabled;
    }

    public int speciesPopulationControlIntervalSeconds() {
        return speciesPopulationControlIntervalSeconds;
    }

    public int speciesPopulationControlUnlockBelowOffset() {
        return speciesPopulationControlUnlockBelowOffset;
    }

    public boolean speciesPopulationControlAnnounceChanges() {
        return speciesPopulationControlAnnounceChanges;
    }

    /** Display name -> cap; names are matched case-insensitively by the scheduler. */
    public Map<String, Integer> speciesPopulationCaps() {
        return speciesPopulationCaps;
    }

    /**
     * Minutes between automatic RCON {@code wipecorpses}; {@code 0} disables the scheduler.
     */
    public String scheduledWipecorpsesEnabledMode() {
        return scheduledWipecorpsesEnabledMode;
    }

    public int scheduledWipecorpsesDynamicMaxPlayers() {
        return scheduledWipecorpsesDynamicMaxPlayers;
    }

    public int scheduledWipecorpsesDynamicEnablePercent() {
        return scheduledWipecorpsesDynamicEnablePercent;
    }

    public int scheduledWipecorpsesDynamicDisableGraceSeconds() {
        return scheduledWipecorpsesDynamicDisableGraceSeconds;
    }

    /**
     * Minutes between automatic RCON {@code wipecorpses}; {@code 0} means no interval.
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

    /**
     * When true and {@link #killFlavorPack()} {@linkplain KillFlavorPack#hasAny() has quips}, mirrored kill lines
     * use templates from {@code kill-flavor.yml} beside {@code config.yml} (or {@link #ingameChatLogKillFlavorPath()}).
     */
    public boolean ingameChatLogKillFlavorEnabled() {
        return ingameChatLogKillFlavorEnabled;
    }

    /** YAML path used for kill flavor templates (same directory as {@code config.yml} by default). */
    public Path killFlavorYamlPath() {
        return killFlavorYamlPath;
    }

    public KillFlavorPack killFlavorPack() {
        return killFlavorPack;
    }
}
