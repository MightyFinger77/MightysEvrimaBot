package com.isle.evrima.bot.rcon;

import com.isle.evrima.bot.config.BotConfig;
import com.isle.evrima.bot.config.LiveBotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public final class RconService {

    private static final Logger LOG = LoggerFactory.getLogger(RconService.class);

    private final LiveBotConfig live;

    public RconService(LiveBotConfig live) {
        this.live = live;
    }

    public String run(String command) throws IOException {
        LOG.debug("RCON: {}", command);
        BotConfig c = live.get();
        return EvrimaRcon.send(
                c.rconHost(),
                c.rconPort(),
                c.rconPassword(),
                command,
                c.rconTimeoutMs()
        );
    }
}
