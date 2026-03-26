package com.isle.evrima.bot.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Optional on-disk playerdata path under {@code dino_park.playerdata_file} in {@code config.yml}.
 * Safe restore uses a byte-identical snapshot captured at {@code /evrima dino park} time when
 * {@link #captureFileOnPark()} is true.
 */
public record DinoParkPlayerdataFile(
        boolean enabled,
        String pathTemplateRaw,
        boolean captureFileOnPark,
        boolean restoreFromCaptureOnRetrieve
) {
    public static final DinoParkPlayerdataFile DISABLED =
            new DinoParkPlayerdataFile(false, "", false, false);

    public DinoParkPlayerdataFile {
        pathTemplateRaw = pathTemplateRaw == null ? "" : pathTemplateRaw;
    }

    /**
     * Resolves {@code path_template} after substituting {@code {steam_id}} / {@code {steamId64}}.
     * Relative templates are resolved against the directory containing {@code config.yml}.
     */
    public Path resolvedPlayerdataPath(String steamId64, Path configYamlPath) {
        Objects.requireNonNull(configYamlPath, "configYamlPath");
        String sid = steamId64 == null ? "" : steamId64.trim();
        String t = pathTemplateRaw
                .replace("{steam_id}", sid)
                .replace("{steamId64}", sid)
                .trim();
        if (t.isEmpty()) {
            throw new IllegalStateException("dino_park.playerdata_file.path_template is blank");
        }
        Path p = Path.of(t);
        if (p.isAbsolute()) {
            return p.normalize();
        }
        Path parent = configYamlPath.getParent();
        if (parent == null) {
            parent = Path.of(".").toAbsolutePath().normalize();
        }
        return parent.resolve(p).normalize();
    }
}
