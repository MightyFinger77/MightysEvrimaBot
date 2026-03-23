package com.isle.evrima.bot;

import com.isle.evrima.bot.config.BotConfig;
import com.isle.evrima.bot.db.Database;
import com.isle.evrima.bot.discord.BotListener;
import com.isle.evrima.bot.discord.CommandRegistry;
import com.isle.evrima.bot.discord.PopulationDashboardScheduler;
import com.isle.evrima.bot.ecosystem.PopulationDashboardService;
import com.isle.evrima.bot.ecosystem.SpeciesTaxonomy;
import com.isle.evrima.bot.rcon.RconService;
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
        Path cfgPath = Path.of(args.length > 0 ? args[0] : "config.yml");
        BotConfig config = BotConfig.load(cfgPath);

        PermissionService permPreview = new PermissionService(config);
        LOG.info("discord.roles configured: moderator={}, admin={}, head_admin={}",
                permPreview.moderatorRoleCount(), permPreview.adminRoleCount(), permPreview.headAdminRoleCount());

        Database database = new Database(config.databasePath());
        database.migrate();

        RconService rcon = new RconService(config);
        PermissionService permissions = new PermissionService(config);
        SpeciesTaxonomy taxonomy = PopulationDashboardService.loadTaxonomy(cfgPath, config.ecosystemTaxonomyRelative());
        PopulationDashboardService population = new PopulationDashboardService(config, rcon, taxonomy);
        BotListener listener = new BotListener(config, database, rcon, permissions, population);

        JDA jda = JDABuilder.createDefault(config.discordToken())
                .enableIntents(GatewayIntent.GUILD_MEMBERS)
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .setChunkingFilter(ChunkingFilter.ALL)
                .addEventListeners(listener)
                .build();

        jda.awaitReady();
        registerSlashCommands(jda, config);
        new PopulationDashboardScheduler(config, database, population).start(jda);
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
