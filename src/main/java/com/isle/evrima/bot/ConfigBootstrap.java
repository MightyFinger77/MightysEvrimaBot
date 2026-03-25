package com.isle.evrima.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/**
 * On first run, creates a config directory next to the working directory and drops default YAMLs
 * from the JAR (Minecraft-style) only when each file is missing.
 */
public final class ConfigBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigBootstrap.class);

    /** Default folder for brand-new installs (no existing config.yml in known locations). */
    public static final String CONFIGS_DIR_NAME = "configs";
    /** Alternate folder name if you keep YAMLs under {@code config/} instead of {@code configs/}. */
    private static final String CONFIG_DIR_SINGULAR = "config";
    private static final String DEFAULT_CONFIG_FILENAME = "config.yml";
    /** Bundled default template (classpath {@code /config.yml}) copied to disk only when {@code config.yml} is missing. */
    private static final String BUNDLED_CONFIG_TEMPLATE = "config.yml";
    private static final String TAXONOMY_FILENAME = "species-taxonomy.yml";
    private static final String KILL_FLAVOR_FILENAME = "kill-flavor.yml";

    private ConfigBootstrap() {}

    /**
     * If {@code args[0]} is present and non-blank, returns that path as-is (no extraction).
     * Otherwise picks a directory that already contains {@code config.yml} if possible:
     * {@code configs/} first, then {@code config/}; if neither exists, uses {@code configs/} and seeds defaults.
     * Only missing files are written — never overwrites your YAMLs.
     */
    public static Path resolveConfigYamlPath(String[] args) throws IOException {
        if (args != null && args.length > 0 && !args[0].isBlank()) {
            return Path.of(args[0].trim());
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path configsDir = cwd.resolve(CONFIGS_DIR_NAME);
        Path configDir = cwd.resolve(CONFIG_DIR_SINGULAR);
        Path inConfigs = configsDir.resolve(DEFAULT_CONFIG_FILENAME);
        Path inConfig = configDir.resolve(DEFAULT_CONFIG_FILENAME);

        Path dir;
        if (Files.isRegularFile(inConfigs)) {
            dir = configsDir;
        } else if (Files.isRegularFile(inConfig)) {
            dir = configDir;
        } else {
            dir = configsDir;
        }

        Files.createDirectories(dir);
        Path configFile = dir.resolve(DEFAULT_CONFIG_FILENAME);
        extractResourceIfMissing(configFile, BUNDLED_CONFIG_TEMPLATE);
        extractResourceIfMissing(dir.resolve(TAXONOMY_FILENAME), TAXONOMY_FILENAME);
        extractResourceIfMissing(dir.resolve(KILL_FLAVOR_FILENAME), KILL_FLAVOR_FILENAME);
        if (!Files.isRegularFile(configFile)) {
            throw new IOException("Failed to create default config at " + configFile.toAbsolutePath());
        }
        LOG.info("Config directory: {}", dir.toAbsolutePath());
        return configFile;
    }

    /**
     * Ensures {@code species-taxonomy.yml} exists in the same directory as {@code config.yml} (e.g. {@code configs/}),
     * extracting from the JAR if missing. Call after resolving {@code config.yml} so custom {@code java -jar … path}
     * layouts get a default taxonomy too.
     */
    public static void ensureSpeciesTaxonomyBesideConfig(Path configYamlPath) throws IOException {
        Objects.requireNonNull(configYamlPath, "configYamlPath");
        Path parent = configYamlPath.getParent();
        if (parent == null) {
            parent = Path.of(".").toAbsolutePath().normalize();
        }
        Files.createDirectories(parent);
        extractResourceIfMissing(parent.resolve(TAXONOMY_FILENAME), TAXONOMY_FILENAME);
        extractResourceIfMissing(parent.resolve(KILL_FLAVOR_FILENAME), KILL_FLAVOR_FILENAME);
    }

    private static void extractResourceIfMissing(Path target, String resourceName) throws IOException {
        if (Files.isRegularFile(target)) {
            return;
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        InputStream in = cl != null ? cl.getResourceAsStream(resourceName) : null;
        if (in == null) {
            in = ConfigBootstrap.class.getClassLoader().getResourceAsStream(resourceName);
        }
        if (in == null) {
            throw new IOException("Missing bundled resource: " + resourceName + " (rebuild JAR with src/main/resources config.yml, species-taxonomy.yml, kill-flavor.yml on classpath)");
        }
        try (InputStream stream = in) {
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
        }
        LOG.info("Wrote default {} -> {}", resourceName, target.toAbsolutePath());
    }
}
