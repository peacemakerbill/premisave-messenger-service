package com.premisave.messenger.storage;

import com.premisave.messenger.config.CallProperties;
import com.premisave.messenger.dto.response.StorageCheckResponse;
import com.premisave.messenger.exception.HttpStatusException;
import com.premisave.messenger.service.StorageSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Where call recordings are kept: a folder on this server (LOCAL) or a network share that the operating system has
 * mounted (NETWORK). The recorder writes the videos there and this service reads and deletes them.
 *
 * The service never mounts a share itself. In NETWORK mode with require-mount, recording is refused unless the mount path
 * really is a network file system, so a dropped share cannot quietly send videos to the local disk under an empty mount
 * folder. The answer is remembered for a few seconds, because every call response asks for it.
 *
 * The recorder writes into a folder that its Docker mount fixes (CALLS_RECORDINGS_HOST_DIR), so an administrator cannot move
 * that folder from the dashboard. What they can choose is a folder inside it for new recordings, and the free space below
 * which recording is refused. The folder is part of each recording's file name, so earlier recordings are still found.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecordingStorage {

    private static final long CACHE_MILLIS = 10_000;

    private final CallProperties props;
    private final StorageSettingsService settings;

    /** What the storage looks like right now. {@code problem} says why it is not ready, and is null when it is. */
    public record Status(CallProperties.StorageMode mode, boolean ready, String problem, Long freeMb) { }

    private volatile Status cached;
    private volatile long cachedAt;

    /** The folder the recorder writes to, as this service sees it. File names of recordings are relative to it. */
    public Path root() {
        if (props.getRecordingsMode() == CallProperties.StorageMode.NETWORK) {
            String mount = props.getRecordingsNetworkMountPath();
            if (mount == null || mount.isBlank()) {
                throw new IllegalStateException("CALLS_RECORDINGS_MODE=NETWORK needs CALLS_RECORDINGS_NETWORK_MOUNT_PATH");
            }
            String dir = props.getRecordingsNetworkDirectory() == null ? "" : props.getRecordingsNetworkDirectory().trim();
            Path base = Paths.get(mount.trim()).toAbsolutePath().normalize();
            return dir.isEmpty() ? base : base.resolve(dir).normalize();
        }
        String local = props.getRecordingsDir();
        if (local == null || local.isBlank()) {
            throw new IllegalStateException("CALLS_RECORDINGS_DIR must not be empty in LOCAL mode");
        }
        return Paths.get(local.trim()).toAbsolutePath().normalize();
    }

    /** The folder inside {@link #root()} that new recordings go to, from the dashboard. Empty means the folder itself. */
    public String subfolder() {
        return settings.recordingsSubfolder();
    }

    /** The folder new recordings are written to: the root, or the chosen folder inside it. */
    public Path target() {
        Path root = root();
        String sub = subfolder();
        return sub.isEmpty() ? root : root.resolve(sub).normalize();
    }

    /** The start of the file name of a new recording: the chosen folder and a slash, or nothing. */
    public String fileNamePrefix() {
        String sub = subfolder();
        return sub.isEmpty() ? "" : sub + "/";
    }

    /** Forget the remembered state, so the next question looks again. Used after a setting is saved. */
    public void refresh() {
        cached = null;
        cachedAt = 0;
    }

    /**
     * Tries a folder inside the recorder's shared folder without saving anything: creates it and writes a small file. The
     * shared folder must be ready (mounted, enough space) as well.
     */
    public StorageCheckResponse tryFolder(String subfolder) {
        String clean;
        try {
            clean = StoragePaths.folderName(subfolder, "The recordings folder");
        } catch (IllegalArgumentException e) {
            return new StorageCheckResponse(false, e.getMessage(), null, null);
        }
        Path folder;
        try {
            Path root = root();
            folder = clean.isEmpty() ? root : root.resolve(clean).normalize();
            if (!folder.startsWith(root)) {
                return new StorageCheckResponse(false, "The folder must stay inside the shared folder", folder.toString(), null);
            }
            if (props.getRecordingsMode() == CallProperties.StorageMode.NETWORK && props.isRecordingsNetworkRequireMount()) {
                Path mount = Paths.get(props.getRecordingsNetworkMountPath().trim()).toAbsolutePath().normalize();
                if (!MountCheck.isOnNetworkShare(mount)) {
                    return new StorageCheckResponse(false, "The network share is not mounted at " + mount, folder.toString(), null);
                }
            }
        } catch (IllegalStateException e) {
            return new StorageCheckResponse(false, e.getMessage(), null, null);
        }
        try {
            Files.createDirectories(folder);
            Path test = folder.resolve(".write-test-" + UUID.randomUUID());
            Files.writeString(test, "ok");
            Files.deleteIfExists(test);
            return new StorageCheckResponse(true, null, folder.toString(), Files.getFileStore(folder).getUsableSpace() / (1024 * 1024));
        } catch (IOException | RuntimeException e) {
            return new StorageCheckResponse(false, "The service cannot write to " + folder + ": " + e.getMessage(),
                    folder.toString(), null);
        }
    }

    /** The current state, checked at most every few seconds. */
    public Status status() {
        long now = System.currentTimeMillis();
        Status s = cached;
        if (s != null && now - cachedAt < CACHE_MILLIS) return s;
        Status before = cached;
        s = check();
        cached = s;
        cachedAt = now;
        if (before == null || before.ready() != s.ready()) {
            if (s.ready()) {
                log.info("Recording storage ({}) is ready at {}", s.mode(), location());
            } else {
                log.warn("Recording storage ({}) is not ready: {}", s.mode(), s.problem());
            }
        }
        return s;
    }

    public boolean ready() {
        return status().ready();
    }

    /** Throws a 503 with a plain reason when recordings cannot be stored right now. */
    public void requireReady() {
        Status s = status();
        if (!s.ready()) {
            throw HttpStatusException.storageUnavailable("Recording storage is not available right now");
        }
    }

    /** A short description of where the videos go, for the administrators. */
    public String location() {
        try {
            return target().toString();
        } catch (IllegalStateException e) {
            return "";
        }
    }

    private Status check() {
        CallProperties.StorageMode mode = props.getRecordingsMode();
        Path root;
        try {
            root = target();
        } catch (IllegalStateException e) {
            return new Status(mode, false, e.getMessage(), null);
        }
        try {
            if (mode == CallProperties.StorageMode.NETWORK && props.isRecordingsNetworkRequireMount()) {
                Path mount = Paths.get(props.getRecordingsNetworkMountPath().trim()).toAbsolutePath().normalize();
                if (!MountCheck.isOnNetworkShare(mount)) {
                    return new Status(mode, false, "The network share is not mounted at " + mount, null);
                }
            }
            if (!Files.isDirectory(root)) {
                Files.createDirectories(root);
            }
            if (!Files.isReadable(root)) {
                return new Status(mode, false, "The recordings folder cannot be read: " + root, null);
            }
            long freeMb = Files.getFileStore(root).getUsableSpace() / (1024 * 1024);
            int min = settings.recordingsMinFreeMb();
            if (min > 0 && freeMb < min) {
                return new Status(mode, false, "Only " + freeMb + " MB free, less than the " + min + " MB required", freeMb);
            }
            return new Status(mode, true, null, freeMb);
        } catch (IOException | RuntimeException e) {
            log.warn("Recording storage check failed for {}: {}", root, e.getMessage());
            return new Status(mode, false, "The recordings folder is not usable: " + e.getMessage(), null);
        }
    }
}
