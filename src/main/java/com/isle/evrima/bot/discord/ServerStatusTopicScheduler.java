package com.isle.evrima.bot.discord;

import com.isle.evrima.bot.config.BotConfig;
import com.isle.evrima.bot.db.Database;
import com.isle.evrima.bot.ecosystem.PopulationDashboardService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.sql.SQLException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Updates Discord text channel topics with RCON population stats (similar to Minecraft “server list” plugins).
 * Requires {@link Permission#MANAGE_CHANNEL} on each channel. Discord limits topics to 1024 characters.
 */
public final class ServerStatusTopicScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ServerStatusTopicScheduler.class);
    private static final int DISCORD_TOPIC_MAX = 1024;
    private static final DateTimeFormatter LAST_UPDATE_FMT =
            DateTimeFormatter.ofPattern("EEE, d MMM uuuu HH:mm:ss z", Locale.ENGLISH);

    private final BotConfig config;
    private final Database database;
    private final PopulationDashboardService population;
    private final Set<Long> warnedPermissionChannelIds = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean warnedTimezone = new AtomicBoolean(false);

    public ServerStatusTopicScheduler(BotConfig config, Database database, PopulationDashboardService population) {
        this.config = Objects.requireNonNull(config, "config");
        this.database = Objects.requireNonNull(database, "database");
        this.population = Objects.requireNonNull(population, "population");
    }

    public void start(JDA jda) {
        List<Long> chIds = config.serverStatusTopicChannelIds();
        if (chIds.isEmpty()) {
            return;
        }
        int minutes = Math.max(1, config.serverStatusTopicIntervalMinutes());
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "evrima-server-status-topic");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleAtFixedRate(() -> runSafe(jda, chIds), 50, minutes * 60L, TimeUnit.SECONDS);
        LOG.info("Server status channel topic: {} channel(s) every {} minute(s)", chIds.size(), minutes);
    }

    private void runSafe(JDA jda, List<Long> channelIds) {
        try {
            runOnce(jda, channelIds);
        } catch (SQLException | IOException e) {
            LOG.warn("server_status_topic tick failed: {}", e.toString());
        } catch (Exception e) {
            LOG.warn("server_status_topic tick failed: {}", e.toString());
        }
    }

    private void runOnce(JDA jda, List<Long> channelIds) throws SQLException, IOException {
        PopulationDashboardService.SnapshotResult res = population.snapshot(true);
        database.recordSteamIdsFromPlayerlistRaw(res.data().rawPlayerlist());
        long unique = database.countSeenServerSteamIds();

        String topic = buildTopic(res, unique);
        if (topic.length() > DISCORD_TOPIC_MAX) {
            topic = topic.substring(0, DISCORD_TOPIC_MAX - 1) + "…";
        }
        String finalTopic = topic;

        for (long channelId : channelIds) {
            applyTopic(jda, channelId, finalTopic);
        }
    }

    private void applyTopic(JDA jda, long channelId, String finalTopic) {
        TextChannel ch = jda.getTextChannelById(channelId);
        if (ch == null) {
            LOG.warn("server_status_topic channel_id {} — channel not visible to bot", channelId);
            return;
        }
        Member self = ch.getGuild().getSelfMember();
        if (!self.hasPermission(ch, Permission.MANAGE_CHANNEL)) {
            if (warnedPermissionChannelIds.add(channelId)) {
                LOG.warn("server_status_topic: bot needs Manage Channels on #{} ({})", ch.getName(), channelId);
            }
            return;
        }

        ch.getManager().setTopic(finalTopic).queue(
                ok -> { },
                err -> LOG.warn("server_status_topic: setTopic failed for {}: {}", channelId, err.toString()));
    }

    private String buildTopic(PopulationDashboardService.SnapshotResult res, long uniqueSeen) {
        int n = Math.max(0, res.data().referencePlayerTotal());
        int max = config.serverStatusTopicMaxPlayers();
        StringBuilder sb = new StringBuilder(128);
        if (max > 0) {
            sb.append(n).append("/").append(max).append(" players online");
        } else {
            sb.append(n).append(" players online");
        }
        if (config.serverStatusTopicShowUniqueSeen()) {
            sb.append(" | ").append(uniqueSeen).append(" unique players seen");
        }
        if (config.serverStatusTopicShowBridgeUptime()) {
            long min = Math.max(0L, ManagementFactory.getRuntimeMXBean().getUptime() / 60_000L);
            sb.append(" | Bridge up ").append(min).append(" min");
        }
        sb.append(" | Last update: ").append(formatNow());
        return sb.toString();
    }

    private String formatNow() {
        ZoneId z = resolveZone();
        return ZonedDateTime.now(z).format(LAST_UPDATE_FMT);
    }

    private ZoneId resolveZone() {
        String id = config.serverStatusTopicTimezoneId();
        if (id == null || id.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(id.trim());
        } catch (Exception e) {
            if (warnedTimezone.compareAndSet(false, true)) {
                LOG.warn("server_status_topic.timezone invalid '{}', using system default", id);
            }
            return ZoneId.systemDefault();
        }
    }
}
