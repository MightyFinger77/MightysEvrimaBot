package com.isle.evrima.bot.rcon;

import com.isle.evrima.bot.config.BotConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public final class RconService {

    private static final Logger LOG = LoggerFactory.getLogger(RconService.class);

    private final BotConfig config;

    public RconService(BotConfig config) {
        this.config = config;
    }

    public String run(String command) throws IOException {
        LOG.debug("RCON: {}", command);
        return EvrimaRcon.send(
                config.rconHost(),
                config.rconPort(),
                config.rconPassword(),
                command,
                config.rconTimeoutMs()
        );
    }
}
