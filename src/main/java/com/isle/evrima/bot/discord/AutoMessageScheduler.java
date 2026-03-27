package com.isle.evrima.bot.discord;

import com.isle.evrima.bot.config.AutoMessagesConfig;
import com.isle.evrima.bot.config.BotConfig;
import com.isle.evrima.bot.config.LiveBotConfig;
import com.isle.evrima.bot.rcon.RconService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends rotating in-game tips via RCON {@code announce} (MightyTips-style: sequential or random without repeat until cycled).
 */
public final class AutoMessageScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(AutoMessageScheduler.class);
    /** Same cap as {@link com.isle.evrima.bot.schedule.ScheduledCorpseWipeScheduler} pre-wipe announce. */
    private static final int ANNOUNCE_MAX_LEN = 400;
    private static final int INITIAL_DELAY_SEC = 45;

    private final LiveBotConfig live;
    private final RconService rcon;
    private final Object randomLock = new Object();
    private ScheduledExecutorService executor;
    private final AtomicInteger sequentialIndex = new AtomicInteger(0);
    private List<Integer> randomPool = new ArrayList<>();
    private int randomPoolCursor;

    public AutoMessageScheduler(LiveBotConfig live, RconService rcon) {
        this.live = Objects.requireNonNull(live, "live");
        this.rcon = Objects.requireNonNull(rcon, "rcon");
    }

    public synchronized void start() {
        restart();
    }

    /**
     * Re-reads config from disk via {@link LiveBotConfig#get()} and reschedules. Call after admin reload.
     */
    public synchronized void restart() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        BotConfig cfg = live.get();
        AutoMessagesConfig am = cfg.autoMessages();
        if (!am.enabled() || am.messages().isEmpty()) {
            LOG.info("Auto-messages: disabled (enabled={}, messages={})",
                    am.enabled(), am.messages().size());
            sequentialIndex.set(0);
            synchronized (randomLock) {
                randomPool.clear();
                randomPoolCursor = 0;
            }
            return;
        }
        int sec = am.intervalSeconds();
        rebuildRandomState(am);
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "evrima-auto-messages");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::tick, INITIAL_DELAY_SEC, sec, TimeUnit.SECONDS);
        LOG.info("Auto-messages: RCON announce every {}s (mode={}, {} message(s), {})",
                sec, am.sequential() ? "sequential" : "random", am.messages().size(),
                am.sourcePath() != null ? am.sourcePath() : "n/a");
    }

    private void rebuildRandomState(AutoMessagesConfig am) {
        synchronized (randomLock) {
            randomPool.clear();
            int n = am.messages().size();
            for (int i = 0; i < n; i++) {
                randomPool.add(i);
            }
            Collections.shuffle(randomPool);
            randomPoolCursor = 0;
        }
        sequentialIndex.set(0);
    }

    private void tick() {
        try {
            BotConfig cfg = live.get();
            AutoMessagesConfig am = cfg.autoMessages();
            if (!am.enabled() || am.messages().isEmpty()) {
                return;
            }
            List<String> msgs = am.messages();
            String raw;
            if (am.sequential()) {
                int i = Math.floorMod(sequentialIndex.getAndIncrement(), msgs.size());
                raw = msgs.get(i);
            } else {
                synchronized (randomLock) {
                    if (randomPool.isEmpty() || randomPoolCursor >= randomPool.size()) {
                        rebuildRandomState(am);
                    }
                    int idx = randomPool.get(randomPoolCursor++);
                    raw = msgs.get(idx);
                }
            }
            String payload = sanitizeAnnounce(raw);
            if (payload.isEmpty()) {
                return;
            }
            rcon.run("announce " + payload);
        } catch (IOException e) {
            LOG.warn("auto-messages RCON failed: {}", e.toString());
        } catch (Exception e) {
            LOG.warn("auto-messages tick: {}", e.toString());
        }
    }

    private static String sanitizeAnnounce(String raw) {
        String s = raw == null ? "" : raw.replace('\r', ' ').replace('\n', ' ').trim();
        if (s.length() > ANNOUNCE_MAX_LEN) {
            s = s.substring(0, ANNOUNCE_MAX_LEN);
        }
        return s;
    }
}
