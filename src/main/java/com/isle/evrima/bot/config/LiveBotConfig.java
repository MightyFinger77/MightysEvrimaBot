package com.isle.evrima.bot.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Holds the current {@link BotConfig} and reloads it from disk after admin commands update {@code config.yml}.
 */
public final class LiveBotConfig {

    private final Path yamlPath;
    private volatile BotConfig config;

    public LiveBotConfig(Path yamlPath, BotConfig initial) {
        this.yamlPath = yamlPath.toAbsolutePath().normalize();
        this.config = Objects.requireNonNull(initial, "initial");
    }

    public Path yamlPath() {
        return yamlPath;
    }

    public BotConfig get() {
        return config;
    }

    public synchronized BotConfig reloadFromDisk() throws IOException {
        config = BotConfig.load(yamlPath);
        return config;
    }
}
