package com.isle.evrima.bot;

import com.isle.evrima.bot.config.BotConfig;
import com.isle.evrima.bot.config.EconomyParkingSlotsConfig;
import com.isle.evrima.bot.config.LiveBotConfig;
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
import com.isle.evrima.bot.schedule.RconWriteGuard;
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

    public static void main(String[] args) throws Exception {
        Path cfgPath = ConfigBootstrap.resolveConfigYamlPath(args);
        ConfigMigration.migrateIfNeeded(cfgPath);
        ConfigBootstrap.ensureSpeciesTaxonomyBesideConfig(cfgPath);
        BotConfig initial = BotConfig.load(cfgPath);
        LiveBotConfig live = new LiveBotConfig(cfgPath, initial);

        PermissionService permPreview = new PermissionService(live);
        LOG.info("discord.roles configured: moderator={}, admin={}, head_admin={}",
                permPreview.moderatorRoleCount(), permPreview.adminRoleCount(), permPreview.headAdminRoleCount());

        Database database = new Database(live.get().databasePath());
        database.migrate();

        RconService rcon = new RconService(live);
        RconWriteGuard rconGuard = new RconWriteGuard();
        PermissionService permissions = new PermissionService(live);
        SpeciesTaxonomy taxonomy = PopulationDashboardService.loadTaxonomy(cfgPath);
        PopulationDashboardService population = new PopulationDashboardService(live, rcon, taxonomy);
        ScheduledCorpseWipeScheduler corpseWipe = new ScheduledCorpseWipeScheduler(live, rcon, population, rconGuard);
        SpeciesPopulationControlScheduler speciesControl = new SpeciesPopulationControlScheduler(live, rcon, population, rconGuard);
        BotListener listener = new BotListener(live, database, rcon, permissions, population, speciesControl, corpseWipe);

        JDA jda = JDABuilder.createDefault(live.get().discordToken())
                .enableIntents(GatewayIntent.GUILD_MEMBERS)
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .setChunkingFilter(ChunkingFilter.ALL)
                .addEventListeners(listener)
                .build();

        jda.awaitReady();
        registerSlashCommands(jda, live.get());
        new PopulationDashboardScheduler(live, database, population).start(jda);
        new ServerStatusTopicScheduler(live, database, population).start(jda);
        new IngameChatLogScheduler(live, database, rcon).start(jda);
        new AdaptiveAiDensityScheduler(live, rcon, population, database, rconGuard).start();
        speciesControl.start();
        corpseWipe.start();
        logEconomyAndDinoParking(live.get());
        LOG.info("EvrimaServerBot logged in as {}", jda.getSelfUser().getName());
    }

    private static void logEconomyAndDinoParking(BotConfig cfg) {
        EconomyParkingSlotsConfig ps = cfg.economyParkingSlots();
        if (ps.enabled()) {
            LOG.info("Economy: daily spin {}-{} pts (UTC day); parking_slots enabled (default={}, max={}, base_price={}, multiplier={})",
                    cfg.dailySpinMin(), cfg.dailySpinMax(),
                    ps.defaultSlots(), ps.maxSlots(), ps.basePricePerSlot(), ps.priceMultiplier());
        } else {
            LOG.info("Economy: daily spin {}-{} pts (UTC day); parking_slots disabled (unlimited /evrima dino park slots)",
                    cfg.dailySpinMin(), cfg.dailySpinMax());
        }
        var pdf = cfg.dinoParkPlayerdataFile();
        var lo = cfg.dinoParkLogoutAutosave();
        String tmpl = pdf.pathTemplateRaw();
        if (tmpl.length() > 120) {
            tmpl = tmpl.substring(0, 117) + "...";
        }
        LOG.info("Dino park: purge_on_kill_log={} (session slot only) deaths_per_slot={}; retrieve_cooldown_seconds={}; playerdata_file enabled={} capture_on_park={} restore_on_retrieve={}; logout_autosave={}; path_template={}",
                cfg.dinoParkPurgeOnKillLog(),
                cfg.dinoParkPurgeOnKillDeathsPerSlot(),
                cfg.dinoParkRetrieveCooldownSeconds(),
                pdf.enabled(),
                pdf.captureFileOnPark(),
                pdf.restoreFromCaptureOnRetrieve(),
                lo.enabled(),
                pdf.enabled() && !tmpl.isBlank() ? tmpl : "(n/a or blank)");
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
