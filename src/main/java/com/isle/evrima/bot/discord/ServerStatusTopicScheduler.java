package com.isle.evrima.bot.discord;

import com.isle.evrima.bot.config.BotConfig;
import com.isle.evrima.bot.config.LiveBotConfig;
import com.isle.evrima.bot.db.Database;
import com.isle.evrima.bot.ecosystem.PopulationDashboardService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.RestAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.sql.SQLException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Updates Discord text channel topics with RCON population stats (similar to Minecraft “server list” plugins).
 * Requires {@link Permission#MANAGE_CHANNEL} on each channel. Discord limits topics to 1024 characters and
 * rate-limits {@code PATCH /channels} heavily — this scheduler skips API calls when stats are unchanged (even
 * though the “Last update” time would change) and spaces out multi-channel updates.
 */
public final class ServerStatusTopicScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ServerStatusTopicScheduler.class);
    private static final int DISCORD_TOPIC_MAX = 1024;
    /** Single channel: small gap is enough. Multiple channels use {@link BotConfig#serverStatusTopicMultiChannelStaggerSeconds()}. */
    private static final int STAGGER_SECONDS_SINGLE = 5;
    private static final String KV_STATS_FINGERPRINT = "server_status_topic_stats_fingerprint";
    private static final DateTimeFormatter LAST_UPDATE_FMT =
            DateTimeFormatter.ofPattern("EEE, d MMM uuuu HH:mm:ss z", Locale.ENGLISH);

    private final LiveBotConfig live;
    private final Database database;
    private final PopulationDashboardService population;
    private final Set<Long> warnedPermissionChannelIds = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean warnedTimezone = new AtomicBoolean(false);
    /** Delays between sequential topic PATCHes (multi-channel); set in {@link #start}. */
    private volatile ScheduledExecutorService topicChainScheduler;
    /** Prevents a new multi-channel rollout while a previous async chain is still PATCHing. */
    private final AtomicBoolean topicMultiRolloutBusy = new AtomicBoolean(false);

    public ServerStatusTopicScheduler(LiveBotConfig live, Database database, PopulationDashboardService population) {
        this.live = Objects.requireNonNull(live, "live");
        this.database = Objects.requireNonNull(database, "database");
        this.population = Objects.requireNonNull(population, "population");
    }

    public void start(JDA jda) {
        BotConfig config = live.get();
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
        topicChainScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "evrima-server-status-topic-chain");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleAtFixedRate(() -> runSafe(jda, chIds), 50, minutes * 60L, TimeUnit.SECONDS);
        int staggerMulti = config.serverStatusTopicMultiChannelStaggerSeconds();
        int stagger = chIds.size() <= 1 ? STAGGER_SECONDS_SINGLE : staggerMulti;
        LOG.info("Server status channel topic: {} channel(s) every {} minute(s); multi: {}s after each PATCH completes before next channel",
                chIds.size(), minutes, stagger);
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

        String fingerprint = buildStatsFingerprint(res);
        Optional<String> prevFp = database.getBotKv(KV_STATS_FINGERPRINT);
        if (prevFp.isPresent() && prevFp.get().equals(fingerprint)) {
            return;
        }

        String topic = buildTopic(res, unique);
        if (topic.length() > DISCORD_TOPIC_MAX) {
            topic = topic.substring(0, DISCORD_TOPIC_MAX - 1) + "…";
        }
        String finalTopic = topic;

        List<TextChannel> applicable = new ArrayList<>();
        for (long channelId : channelIds) {
            TextChannel ch = jda.getTextChannelById(channelId);
            if (ch == null) {
                LOG.warn("server_status_topic channel_id {} — channel not visible to bot", channelId);
                continue;
            }
            Member self = ch.getGuild().getSelfMember();
            if (!self.hasPermission(ch, Permission.MANAGE_CHANNEL)) {
                if (warnedPermissionChannelIds.add(channelId)) {
                    LOG.warn("server_status_topic: bot needs Manage Channels on #{} ({})", ch.getName(), channelId);
                }
                continue;
            }
            applicable.add(ch);
        }
        if (applicable.isEmpty()) {
            return;
        }

        int staggerSec = applicable.size() <= 1
                ? STAGGER_SECONDS_SINGLE
                : live.get().serverStatusTopicMultiChannelStaggerSeconds();
        AtomicInteger remaining = new AtomicInteger(applicable.size());
        ScheduledExecutorService chainExec = topicChainScheduler;
        if (applicable.size() <= 1 || chainExec == null) {
            for (TextChannel ch : applicable) {
                RestAction<Void> action = ch.getManager().setTopic(finalTopic);
                final String fpToSave = fingerprint;
                action.queue(
                        ok -> finishTopicBatch(fpToSave, remaining, null),
                        err -> {
                            LOG.warn("server_status_topic: setTopic failed for {}: {}", ch.getId(), err.toString());
                            finishTopicBatch(fpToSave, remaining, null);
                        });
            }
            return;
        }
        if (!topicMultiRolloutBusy.compareAndSet(false, true)) {
            LOG.debug("server_status_topic: multi-channel rollout still in progress — skipping this tick");
            return;
        }
        Runnable releaseMultiRollout = () -> topicMultiRolloutBusy.set(false);
        applyTopicChain(chainExec, applicable, finalTopic, fingerprint, staggerSec, 0, remaining, releaseMultiRollout);
    }

    /**
     * One {@code PATCH /channels} at a time: next update runs only after the previous RestAction finishes (success or
     * failure), then waits {@code staggerSec}. Avoids overlapping PATCHes from fixed {@code queueAfter} offsets, which
     * still tripped Discord’s shared guild bucket (soft 429).
     */
    private void applyTopicChain(
            ScheduledExecutorService delayExec,
            List<TextChannel> channels,
            String finalTopic,
            String fingerprint,
            int staggerSec,
            int index,
            AtomicInteger remaining,
            Runnable releaseMultiRollout) {
        if (index >= channels.size()) {
            return;
        }
        TextChannel ch = channels.get(index);
        final String fpToSave = fingerprint;
        ch.getManager().setTopic(finalTopic).queue(
                ok -> {
                    finishTopicBatch(fpToSave, remaining, releaseMultiRollout);
                    scheduleNextTopicPatch(
                            delayExec, channels, finalTopic, fingerprint, staggerSec, index + 1, remaining, releaseMultiRollout);
                },
                err -> {
                    LOG.warn("server_status_topic: setTopic failed for {}: {}", ch.getId(), err.toString());
                    finishTopicBatch(fpToSave, remaining, releaseMultiRollout);
                    scheduleNextTopicPatch(
                            delayExec, channels, finalTopic, fingerprint, staggerSec, index + 1, remaining, releaseMultiRollout);
                });
    }

    private void scheduleNextTopicPatch(
            ScheduledExecutorService delayExec,
            List<TextChannel> channels,
            String finalTopic,
            String fingerprint,
            int staggerSec,
            int nextIndex,
            AtomicInteger remaining,
            Runnable releaseMultiRollout) {
        if (nextIndex >= channels.size()) {
            return;
        }
        delayExec.schedule(
                () -> applyTopicChain(
                        delayExec, channels, finalTopic, fingerprint, staggerSec, nextIndex, remaining, releaseMultiRollout),
                staggerSec,
                TimeUnit.SECONDS);
    }

    private void finishTopicBatch(String fingerprint, AtomicInteger remaining, Runnable whenAllPatchesDone) {
        if (remaining.decrementAndGet() != 0) {
            return;
        }
        if (whenAllPatchesDone != null) {
            whenAllPatchesDone.run();
        }
        try {
            database.putBotKv(KV_STATS_FINGERPRINT, fingerprint);
        } catch (SQLException e) {
            LOG.warn("server_status_topic: could not save stats fingerprint", e);
        }
    }

    /**
     * Stable string for equality — excludes “Last update” timestamp.
     * Does <b>not</b> include {@code uniqueSeen}: that value grows whenever new Steam IDs appear in {@code playerlist}
     * and would force a multi-channel PATCH storm (Discord 429 on {@code PATCH /channels}). The topic line can still
     * show “unique players seen”; it refreshes when online count or other fingerprint fields change.
     */
    private String buildStatsFingerprint(PopulationDashboardService.SnapshotResult res) {
        BotConfig config = live.get();
        int n = Math.max(0, res.data().referencePlayerTotal());
        int max = config.serverStatusTopicMaxPlayers();
        StringBuilder sb = new StringBuilder(32);
        sb.append(n).append('|').append(max).append('|');
        if (config.serverStatusTopicShowBridgeUptime()) {
            sb.append(ManagementFactory.getRuntimeMXBean().getUptime() / 60_000L);
        } else {
            sb.append('-');
        }
        return sb.toString();
    }

    private String buildTopic(PopulationDashboardService.SnapshotResult res, long uniqueSeen) {
        BotConfig config = live.get();
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
        String id = live.get().serverStatusTopicTimezoneId();
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
