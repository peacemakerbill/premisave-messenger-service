package com.premisave.messenger.storage;

import com.premisave.messenger.config.CallProperties;
import com.premisave.messenger.exception.HttpStatusException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

/**
 * Where call recordings are kept: a folder on this server (LOCAL) or a network share that the operating system has
 * mounted (NETWORK). The recorder writes the videos there and this service reads and deletes them.
 *
 * The service never mounts a share itself. In NETWORK mode with require-mount, recording is refused unless the mount path
 * really is a network file system, so a dropped share cannot quietly send videos to the local disk under an empty mount
 * folder. The answer is remembered for a few seconds, because every call response asks for it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecordingStorage {

    private static final long CACHE_MILLIS = 10_000;

    private static final Set<String> NETWORK_FS = Set.of(
            "cifs", "smb3", "smbfs", "nfs", "nfs4", "9p", "ceph", "glusterfs",
            "fuse.sshfs", "fuse.rclone", "fuse.davfs", "davfs", "fuse.glusterfs");

    private final CallProperties props;

    /** What the storage looks like right now. {@code problem} says why it is not ready, and is null when it is. */
    public record Status(CallProperties.StorageMode mode, boolean ready, String problem, Long freeMb) { }

    private volatile Status cached;
    private volatile long cachedAt;

    /** The folder the videos are in, as this service sees it. */
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
            return root().toString();
        } catch (IllegalStateException e) {
            return "";
        }
    }

    private Status check() {
        CallProperties.StorageMode mode = props.getRecordingsMode();
        Path root;
        try {
            root = root();
        } catch (IllegalStateException e) {
            return new Status(mode, false, e.getMessage(), null);
        }
        try {
            if (mode == CallProperties.StorageMode.NETWORK && props.isRecordingsNetworkRequireMount()) {
                Path mount = Paths.get(props.getRecordingsNetworkMountPath().trim()).toAbsolutePath().normalize();
                if (!isOnNetworkShare(mount)) {
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
            int min = props.getRecordingsMinFreeMb();
            if (min > 0 && freeMb < min) {
                return new Status(mode, false, "Only " + freeMb + " MB free, less than the " + min + " MB required", freeMb);
            }
            return new Status(mode, true, null, freeMb);
        } catch (IOException | RuntimeException e) {
            log.warn("Recording storage check failed for {}: {}", root, e.getMessage());
            return new Status(mode, false, "The recordings folder is not usable: " + e.getMessage(), null);
        }
    }

    /**
     * True when the file system that holds this path is a network file system. Reads /proc/self/mountinfo and finds the most
     * specific mount containing the path, so a local folder on a separate partition does not pass for a share. Verifies on
     * Linux only; elsewhere it falls back to "the directory exists" and says so in the log.
     */
    private boolean isOnNetworkShare(Path path) {
        Path info = Paths.get("/proc/self/mountinfo");
        if (!Files.isReadable(info)) {
            log.warn("Cannot verify mounts on this OS, only checking that {} exists", path);
            return Files.isDirectory(path);
        }
        try {
            if (!Files.isDirectory(path)) return false;
            Path real = path.toRealPath();
            Path bestMount = null;
            String bestType = null;
            for (String line : Files.readAllLines(info)) {
                // mountinfo: id parent major:minor root mountpoint opts [optional...] - fstype source superopts
                int dash = line.indexOf(" - ");
                String[] pre = (dash >= 0 ? line.substring(0, dash) : line).split(" ");
                String[] post = dash >= 0 ? line.substring(dash + 3).split(" ") : new String[0];
                if (pre.length < 5 || post.length < 1) continue;
                Path mountPoint = Paths.get(unescape(pre[4]));
                if (real.startsWith(mountPoint)
                        && (bestMount == null || mountPoint.getNameCount() >= bestMount.getNameCount())) {
                    bestMount = mountPoint;
                    bestType = post[0];
                }
            }
            if (bestType == null) return false;
            boolean network = NETWORK_FS.contains(bestType);
            if (!network) {
                log.warn("{} is on a '{}' file system, not a network share", real, bestType);
            }
            return network;
        } catch (IOException e) {
            log.error("Could not read mount table: {}", e.getMessage());
            return false;
        }
    }

    /** mountinfo escapes space, tab, newline and backslash as octal. */
    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 3 < s.length() && s.substring(i + 1, i + 4).matches("[0-7]{3}")) {
                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 4), 8));
                i += 3;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
