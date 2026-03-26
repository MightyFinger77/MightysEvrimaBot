package com.isle.evrima.bot.dino;

import com.isle.evrima.bot.config.DinoParkPlayerdataFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes parked on-disk playerdata bytes back to the dedicated server file with backup + atomic replace.
 */
public final class PlayerdataFileRestore {

    private static final Logger LOG = LoggerFactory.getLogger(PlayerdataFileRestore.class);
    private static final Pattern DISK_B64 = Pattern.compile("\"diskUtf8B64\"\\s*:\\s*\"([^\"]*)\"");

    private PlayerdataFileRestore() {}

    /** @param discordLine appended to Discord retrieve message when non-blank */
    public record Result(String discordLine) {}

    /**
     * Reads the configured server file when capture is enabled and the file exists.
     */
    public static Optional<byte[]> captureIfPresent(DinoParkPlayerdataFile cfg, Path configYamlPath, String steamId64) {
        if (!cfg.enabled() || !cfg.captureFileOnPark() || cfg.pathTemplateRaw().isBlank()) {
            return Optional.empty();
        }
        try {
            Path path = cfg.resolvedPlayerdataPath(steamId64, configYamlPath);
            if (!Files.isRegularFile(path)) {
                LOG.info("dino park: playerdata_file capture skipped — not a regular file: {}", path);
                return Optional.empty();
            }
            return Optional.of(Files.readAllBytes(path));
        } catch (Exception e) {
            LOG.warn("dino park: playerdata_file capture failed: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * Restores disk snapshot captured at park when {@link DinoParkPlayerdataFile#restoreFromCaptureOnRetrieve()} is true.
     */
    public static Result restore(
            DinoParkPlayerdataFile cfg,
            Path configYamlPath,
            String steamId64,
            String payloadJson
    ) {
        if (!cfg.enabled() || !cfg.restoreFromCaptureOnRetrieve()) {
            return new Result("");
        }
        Path target;
        try {
            target = cfg.resolvedPlayerdataPath(steamId64, configYamlPath);
        } catch (Exception e) {
            return new Result("\n**Playerdata file:** could not resolve path — " + e.getMessage());
        }

        Optional<byte[]> disk = readDiskSnapshotBytes(payloadJson);
        if (disk.isPresent()) {
            try {
                atomicWriteWithBackup(target, disk.get());
                return new Result("\n**Playerdata file:** wrote **lossless** snapshot to `" + target
                        + "` (previous file copied to `.bak` next to it). **Stop the dedicated server before retrieve** "
                        + "or the game may overwrite your change.");
            } catch (IOException e) {
                LOG.warn("playerdata restore (disk snapshot): {}", e.toString());
                return new Result("\n**Playerdata file:** write failed — " + e.getMessage());
            }
        }

        if (!cfg.pathTemplateRaw().isBlank()) {
            return new Result("\n**Playerdata file:** this slot has **no disk capture** (park with "
                    + "`capture_file_on_park` while the save file exists), so nothing was written.");
        }
        return new Result("");
    }

    static Optional<byte[]> readDiskSnapshotBytes(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Optional.empty();
        }
        Matcher m = DISK_B64.matcher(payloadJson);
        if (!m.find()) {
            return Optional.empty();
        }
        String b64 = m.group(1);
        if (b64.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Base64.getDecoder().decode(b64));
        } catch (IllegalArgumentException e) {
            LOG.warn("parked payload diskUtf8B64 decode failed: {}", e.toString());
            return Optional.empty();
        }
    }

    static void atomicWriteWithBackup(Path target, byte[] content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path backup = backupPath(target);
        if (Files.isRegularFile(target)) {
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        String base = target.getFileName().toString();
        String tmpName = ".evrima-bot-" + base + "." + ThreadLocalRandom.current().nextInt(1_000_000) + ".tmp";
        Path temp = parent != null ? parent.resolve(tmpName) : Path.of(tmpName);
        try {
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private static Path backupPath(Path target) {
        Path parent = target.getParent();
        String name = target.getFileName().toString() + ".bak";
        return parent != null ? parent.resolve(name) : Path.of(name);
    }
}
