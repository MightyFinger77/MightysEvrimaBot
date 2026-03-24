package com.isle.evrima.bot;

import com.isle.evrima.bot.config.BotConfig;
import com.isle.evrima.bot.db.Database;
import com.isle.evrima.bot.discord.BotListener;
import com.isle.evrima.bot.discord.CommandRegistry;
import com.isle.evrima.bot.discord.IngameChatLogScheduler;
import com.isle.evrima.bot.discord.PopulationDashboardScheduler;
import com.isle.evrima.bot.discord.ServerStatusTopicScheduler;
import com.isle.evrima.bot.ecosystem.PopulationDashboardService;
import com.isle.evrima.bot.ecosystem.SpeciesTaxonomy;
import com.isle.evrima.bot.rcon.RconService;
import com.isle.evrima.bot.schedule.AdaptiveAiDensityScheduler;
import com.isle.evrima.bot.schedule.ScheduledCorpseWipeScheduler;
import com.isle.evrima.bot.schedule.SpeciesPopulationControlScheduler;
import com.isle.evrima.bot.security.PermissionService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collections;

public final class EvrimaServerBotApp {

    private static final Logger LOG = LoggerFactory.getLogger(EvrimaServerBotApp.class);

    private static final int MAX_GUILD_CLEAR = 25;
    private static final String KV_SPECIES_CONTROL_ENABLED = "species_population_control_runtime_enabled";
    private static final String KV_SPECIES_CAP_OVERRIDE_PREFIX = "species_population_control_cap_override:";
    private static final String KV_CORPSE_WIPE_ENABLED = "scheduled_wipecorpses_runtime_enabled";
    private static final String KV_CORPSE_WIPE_INTERVAL = "scheduled_wipecorpses_runtime_interval_minutes";
    private static final String KV_CORPSE_WIPE_WARN = "scheduled_wipecorpses_runtime_warn_before_minutes";
    private static final String KV_CORPSE_WIPE_MESSAGE = "scheduled_wipecorpses_runtime_announce_message";
    private static final String KV_CORPSE_WIPE_DYN_MAX = "scheduled_wipecorpses_runtime_dynamic_max_players";
    private static final String KV_CORPSE_WIPE_DYN_PCT = "scheduled_wipecorpses_runtime_dynamic_enable_percent";
    private static final String KV_CORPSE_WIPE_DYN_GRACE = "scheduled_wipecorpses_runtime_dynamic_disable_grace_seconds";

    public static void main(String[] args) throws Exception {
        Path cfgPath = ConfigBootstrap.resolveConfigYamlPath(args);
        ConfigMigration.migrateIfNeeded(cfgPath);
        ConfigBootstrap.ensureSpeciesTaxonomyBesideConfig(cfgPath);
        BotConfig config = BotConfig.load(cfgPath);

        PermissionService permPreview = new PermissionService(config);
        LOG.info("discord.roles configured: moderator={}, admin={}, head_admin={}",
                permPreview.moderatorRoleCount(), permPreview.adminRoleCount(), permPreview.headAdminRoleCount());

        Database database = new Database(config.databasePath());
        database.migrate();

        RconService rcon = new RconService(config);
        PermissionService permissions = new PermissionService(config);
        SpeciesTaxonomy taxonomy = PopulationDashboardService.loadTaxonomy(cfgPath);
        PopulationDashboardService population = new PopulationDashboardService(config, rcon, taxonomy);
        ScheduledCorpseWipeScheduler corpseWipe = new ScheduledCorpseWipeScheduler(config, rcon, population);
        SpeciesPopulationControlScheduler speciesControl = new SpeciesPopulationControlScheduler(config, rcon, population);
        try {
            var persisted = database.getBotKv(KV_SPECIES_CONTROL_ENABLED);
            if (persisted.isPresent()) {
                speciesControl.setEnabled(Boolean.parseBoolean(persisted.get().trim()));
            }
            var capRows = database.listBotKvByPrefix(KV_SPECIES_CAP_OVERRIDE_PREFIX);
            int loaded = 0;
            for (var e : capRows.entrySet()) {
                String species = e.getKey().substring(KV_SPECIES_CAP_OVERRIDE_PREFIX.length());
                try {
                    int cap = Integer.parseInt(e.getValue().trim());
                    speciesControl.setCapOverride(species, cap);
                    loaded++;
                } catch (NumberFormatException nfe) {
                    LOG.warn("Ignoring invalid species cap override {}={}", e.getKey(), e.getValue());
                }
            }
            if (loaded > 0) {
                LOG.info("Loaded {} runtime species cap override(s) from DB", loaded);
            }
            var cwEnabled = database.getBotKv(KV_CORPSE_WIPE_ENABLED);
            if (cwEnabled.isPresent()) {
                String v = cwEnabled.get().trim().toLowerCase();
                if ("true".equals(v) || "false".equals(v) || "dynamic".equals(v)) {
                    corpseWipe.setEnabledMode(v);
                } else {
                    corpseWipe.setEnabledMode(Boolean.parseBoolean(v) ? "true" : "false");
                }
            }
            var cwInterval = database.getBotKvLong(KV_CORPSE_WIPE_INTERVAL);
            if (cwInterval.isPresent()) {
                corpseWipe.setIntervalMinutes((int) cwInterval.getAsLong());
            }
            var cwWarn = database.getBotKvLong(KV_CORPSE_WIPE_WARN);
            if (cwWarn.isPresent()) {
                corpseWipe.setWarnBeforeMinutes((int) cwWarn.getAsLong());
            }
            var cwMsg = database.getBotKv(KV_CORPSE_WIPE_MESSAGE);
            cwMsg.ifPresent(corpseWipe::setAnnounceMessage);
            var cwDynMax = database.getBotKvLong(KV_CORPSE_WIPE_DYN_MAX);
            if (cwDynMax.isPresent()) {
                corpseWipe.setDynamicMaxPlayers((int) cwDynMax.getAsLong());
            }
            var cwDynPct = database.getBotKvLong(KV_CORPSE_WIPE_DYN_PCT);
            if (cwDynPct.isPresent()) {
                corpseWipe.setDynamicEnablePercent((int) cwDynPct.getAsLong());
            }
            var cwDynGrace = database.getBotKvLong(KV_CORPSE_WIPE_DYN_GRACE);
            if (cwDynGrace.isPresent()) {
                corpseWipe.setDynamicDisableGraceSeconds((int) cwDynGrace.getAsLong());
            }
        } catch (Exception e) {
            LOG.warn("Could not read runtime scheduler overrides from DB; using config defaults: {}", e.toString());
        }
        BotListener listener = new BotListener(config, database, rcon, permissions, population, speciesControl, corpseWipe);

        JDA jda = JDABuilder.createDefault(config.discordToken())
                .enableIntents(GatewayIntent.GUILD_MEMBERS)
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .setChunkingFilter(ChunkingFilter.ALL)
                .addEventListeners(listener)
                .build();

        jda.awaitReady();
        registerSlashCommands(jda, config);
        new PopulationDashboardScheduler(config, database, population).start(jda);
        new ServerStatusTopicScheduler(config, database, population).start(jda);
        new IngameChatLogScheduler(config, database).start(jda);
        new AdaptiveAiDensityScheduler(config, rcon, population, database).start();
        speciesControl.start();
        corpseWipe.start();
        LOG.info("EvrimaServerBot logged in as {}", jda.getSelfUser().getName());
    }

    private static void registerSlashCommands(JDA jda, BotConfig config) {
        var cmds = CommandRegistry.allCommands();
        long gid = config.guildId();
        try {
            if (gid != 0L) {
                Guild g = jda.getGuildById(gid);
                if (g != null) {
                    jda.updateCommands().addCommands(Collections.emptyList()).complete();
                    LOG.info("Cleared global application commands (prevents duplicate slash entries)");
                    g.updateCommands().addCommands(cmds).complete();
                    LOG.info("Registered {} slash command roots on guild {}", cmds.size(), gid);
                    return;
                }
                LOG.warn("guild_id {} not found — falling back to global registration", gid);
            }

            int cleared = 0;
            for (Guild gx : jda.getGuilds()) {
                if (cleared >= MAX_GUILD_CLEAR) {
                    LOG.warn("Stopped guild command clear after {} guilds (cap {}). Remaining guilds may still show old copies.",
                            MAX_GUILD_CLEAR, MAX_GUILD_CLEAR);
                    break;
                }
                try {
                    gx.updateCommands().addCommands(Collections.emptyList()).complete();
                    cleared++;
                } catch (Exception ex) {
                    LOG.warn("Could not clear guild commands for {}: {}", gx.getId(), ex.toString());
                }
            }
            jda.updateCommands().addCommands(cmds).complete();
            LOG.info("Registered {} global slash command roots (cleared this bot's commands on {} guilds first)",
                    cmds.size(), cleared);
        } catch (Exception e) {
            LOG.error("Slash command registration failed — fix errors and restart (duplicates or missing commands possible)", e);
        }
    }
}
