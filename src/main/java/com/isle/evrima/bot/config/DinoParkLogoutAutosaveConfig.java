package com.isle.evrima.bot.config;

import java.util.List;

/**
 * {@code dino_park.logout_autosave} — refresh an <b>existing</b> parking slot when log lines match.
 * Only applies when the SteamID is linked in the bot and the user already has a {@code /evrima dino park} row for that SteamID.
 * <p>
 * Soft logout lines refresh immediately. Hard-disconnect lines schedule a delayed refresh after
 * {@link #hardDisconnectDelaySeconds()} (seconds). If that value is {@code 0}, the scheduler uses {@code 300} instead
 * so the body has time to leave the map.
 */
public record DinoParkLogoutAutosaveConfig(
        boolean enabled,
        List<String> softLogoutLineContainsAny,
        List<String> hardDisconnectLineContainsAny,
        int hardDisconnectDelaySeconds
) {
    public static final DinoParkLogoutAutosaveConfig DISABLED =
            new DinoParkLogoutAutosaveConfig(false, List.of(), List.of(), 0);

    public DinoParkLogoutAutosaveConfig {
        softLogoutLineContainsAny = softLogoutLineContainsAny == null ? List.of() : List.copyOf(softLogoutLineContainsAny);
        hardDisconnectLineContainsAny =
                hardDisconnectLineContainsAny == null ? List.of() : List.copyOf(hardDisconnectLineContainsAny);
        if (hardDisconnectDelaySeconds < 0) {
            hardDisconnectDelaySeconds = 0;
        }
        if (hardDisconnectDelaySeconds > 0 && hardDisconnectDelaySeconds < 30) {
            hardDisconnectDelaySeconds = 30;
        }
        if (hardDisconnectDelaySeconds > 86400) {
            hardDisconnectDelaySeconds = 86400;
        }
    }
}
