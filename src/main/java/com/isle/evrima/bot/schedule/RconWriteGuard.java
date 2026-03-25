package com.isle.evrima.bot.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Scheduler-only RCON write guard to avoid hammering commands during reboot/recovery windows.
 */
public final class RconWriteGuard {

    private static final Logger LOG = LoggerFactory.getLogger(RconWriteGuard.class);

    private static final int STABLE_REQUIRED_SECONDS = 120;
    private static final int MIN_PLAYERS_REQUIRED = 1;
    private static final int POST_RESTART_GRACE_SECONDS = 600;
    private static final long BLOCK_LOG_THROTTLE_MS = 60_000L;

    private final AtomicLong healthySinceMs = new AtomicLong(-1L);
    private final AtomicLong reconnectAtMs = new AtomicLong(-1L);
    private final AtomicLong lastBlockedLogMs = new AtomicLong(0L);

    public void observeHealthy() {
        long now = System.currentTimeMillis();
        if (healthySinceMs.get() <= 0L) {
            healthySinceMs.set(now);
            reconnectAtMs.set(now);
        }
    }

    public void observeFailure() {
        healthySinceMs.set(-1L);
    }

    public boolean allowSchedulerWrite(String schedulerName, int onlinePlayers) {
        long now = System.currentTimeMillis();

        long healthySince = healthySinceMs.get();
        if (healthySince <= 0L) {
            logBlockedThrottled(now, schedulerName, onlinePlayers, "no healthy window yet");
            return false;
        }

        long stableSec = Math.max(0L, (now - healthySince) / 1000L);
        if (stableSec < STABLE_REQUIRED_SECONDS) {
            logBlockedThrottled(now, schedulerName, onlinePlayers,
                    "stabilizing " + stableSec + "s/" + STABLE_REQUIRED_SECONDS + "s");
            return false;
        }

        if (onlinePlayers >= MIN_PLAYERS_REQUIRED) {
            return true;
        }

        long reconnectAt = reconnectAtMs.get();
        long sinceReconnectSec = reconnectAt <= 0L ? Long.MAX_VALUE : Math.max(0L, (now - reconnectAt) / 1000L);
        if (sinceReconnectSec < POST_RESTART_GRACE_SECONDS) {
            logBlockedThrottled(now, schedulerName, onlinePlayers,
                    "players=" + onlinePlayers + " and grace "
                            + sinceReconnectSec + "s/" + POST_RESTART_GRACE_SECONDS + "s");
            return false;
        }

        return true;
    }

    private void logBlockedThrottled(long nowMs, String schedulerName, int onlinePlayers, String reason) {
        long prev = lastBlockedLogMs.get();
        if (nowMs - prev < BLOCK_LOG_THROTTLE_MS) {
            return;
        }
        if (lastBlockedLogMs.compareAndSet(prev, nowMs)) {
            LOG.info("rcon_write_guard: blocked {} (online_players={}, reason={})",
                    schedulerName, onlinePlayers, reason);
        }
    }
}
