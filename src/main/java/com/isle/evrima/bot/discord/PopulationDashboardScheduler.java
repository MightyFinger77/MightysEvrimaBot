package com.isle.evrima.bot.discord;

import com.isle.evrima.bot.config.LiveBotConfig;
import com.isle.evrima.bot.db.Database;
import com.isle.evrima.bot.ecosystem.EcosystemEmbeds;
import com.isle.evrima.bot.ecosystem.PopulationDashboardService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Optional periodic embed in a configured channel (edits the same message when possible).
 */
public final class PopulationDashboardScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(PopulationDashboardScheduler.class);
    private static final String KV_MESSAGE = "population_dashboard_message_id";

    private final LiveBotConfig live;
    private final Database database;
    private final PopulationDashboardService population;

    public PopulationDashboardScheduler(LiveBotConfig live, Database database, PopulationDashboardService population) {
        this.live = Objects.requireNonNull(live, "live");
        this.database = Objects.requireNonNull(database, "database");
        this.population = Objects.requireNonNull(population, "population");
    }

    public void start(JDA jda) {
        long chId = live.get().populationDashboardChannelId();
        if (chId == 0L) {
            return;
        }
        int minutes = Math.max(1, live.get().populationDashboardIntervalMinutes());
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "evrima-population-dashboard");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleAtFixedRate(() -> runSafe(jda, chId), 45, minutes * 60L, TimeUnit.SECONDS);
        LOG.info("Population dashboard: channel {} every {} minute(s)", chId, minutes);
    }

    private void runSafe(JDA jda, long channelId) {
        try {
            runOnce(jda, channelId);
        } catch (SQLException | IOException e) {
            LOG.warn("Population dashboard tick failed: {}", e.toString());
        } catch (Exception e) {
            LOG.warn("Population dashboard tick failed: {}", e.toString());
        }
    }

    private void runOnce(JDA jda, long channelId) throws SQLException, IOException {
        TextChannel ch = jda.getTextChannelById(channelId);
        if (ch == null) {
            LOG.warn("population_dashboard.channel_id {} — channel not visible to bot", channelId);
            return;
        }
        if (!ch.canTalk()) {
            LOG.warn("population_dashboard.channel_id {} — missing send permission", channelId);
            return;
        }

        PopulationDashboardService.SnapshotResult res = population.snapshot(true);
        database.recordSteamIdsFromPlayerlistRaw(res.data().rawPlayerlist());

        var embed = EcosystemEmbeds.build(
                live.get().ecosystemTitle(),
                res.data(),
                population.taxonomy(),
                ch.getGuild());

        String kvKey = KV_MESSAGE + "_" + channelId;
        long existingMsg = database.getBotKvLong(kvKey).orElse(0L);

        if (existingMsg != 0L) {
            ch.retrieveMessageById(existingMsg).queue(
                    m -> m.editMessageEmbeds(embed).queue(
                            s -> { },
                            err -> postFresh(ch, kvKey, embed)
                    ),
                    err -> postFresh(ch, kvKey, embed));
        } else {
            postFresh(ch, kvKey, embed);
        }
    }

    private void postFresh(TextChannel ch, String kvKey, net.dv8tion.jda.api.entities.MessageEmbed embed) {
        ch.sendMessageEmbeds(embed).queue(
                m -> {
                    try {
                        database.putBotKv(kvKey, String.valueOf(m.getIdLong()));
                    } catch (SQLException e) {
                        LOG.warn("Could not store dashboard message id", e);
                    }
                },
                err -> LOG.warn("Population dashboard send failed: {}", err.toString())
        );
    }
}
