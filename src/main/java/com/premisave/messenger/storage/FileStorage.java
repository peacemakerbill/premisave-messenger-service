package com.premisave.messenger.storage;

import com.premisave.messenger.config.StorageProperties;
import com.premisave.messenger.exception.HttpStatusException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Disk access for attachments. The only class that touches the file system.
 *
 * Rules it enforces:
 * - Stored files are named by a random id with no extension and no user
 *   input, so a filename can never steer a path.
 * - Every lookup is resolved against the base directory and rejected if it
 *   escapes it.
 * - Writes go to a temp file first and are renamed into place, so a reader
 *   never sees a half-written file.
 * - In NETWORK mode with require-mount, nothing is written unless the
 *   configured path is really a mounted file system. Otherwise a dropped
 *   share would quietly fill the local disk under the empty mount folder.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileStorage {

    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final long MOUNT_CHECK_CACHE_MS = 10_000L;
    private static final int HEAD_BYTES = 16;

    private final StorageProperties props;

    private Path baseDir;
    private volatile long lastMountCheckAt;
    private volatile boolean lastMountOk;

    @PostConstruct
    void init() {
        baseDir = computeBaseDir();
        if (props.getMode() == StorageProperties.Mode.NETWORK && props.getNetwork().isRequireMount()) {
            // Fail fast at startup instead of on the first upload.
            ensureMounted();
        }
        try {
            Files.createDirectories(baseDir);
            if (!Files.isWritable(baseDir)) {
                throw new IllegalStateException("Storage directory is not writable: " + baseDir);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create storage directory " + baseDir, e);
        }
        log.info("Attachment storage ready: mode={} dir={} maxFileSize={}",
                props.getMode(), baseDir, props.getMaxFileSize());
    }

    public StoredFile store(InputStream in, String chatId, long maxBytes) {
        if (chatId == null || !SAFE_SEGMENT.matcher(chatId).matches()) {
            throw new IllegalArgumentException("Invalid chat id");
        }
        ensureAvailable();

        String id = UUID.randomUUID().toString();
        String relativeDir = "chats/" + chatId + "/" + YearMonth.now();
        Path dir = resolve(relativeDir);
        Path tmp = dir.resolve(".upload-" + id + ".tmp");
        Path target = dir.resolve(id);

        try {
            Files.createDirectories(dir);

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            byte[] head = new byte[0];
            byte[] buf = new byte[64 * 1024];

            try (var out = Files.newOutputStream(tmp)) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > maxBytes) {
                        throw HttpStatusException.tooLarge("File is larger than " + (maxBytes / (1024 * 1024)) + " MB");
                    }
                    if (head.length < HEAD_BYTES) {
                        head = Arrays.copyOf(buf, Math.min(n, HEAD_BYTES));
                    }
                    digest.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            if (total == 0) {
                throw new IllegalArgumentException("File is empty");
            }

            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target);
            }
            return new StoredFile(relativeDir + "/" + id, total, HexFormat.of().formatHex(digest.digest()), head);

        } catch (IOException e) {
            log.error("Failed to write attachment to {}: {}", dir, e.getMessage());
            throw HttpStatusException.storageUnavailable("File storage is not available right now");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // temp file already moved or gone
            }
        }
    }

    /** Resolves a stored path for reading. Throws if the file is missing. */
    public Path open(String relativePath) {
        ensureAvailable();
        Path p = resolve(relativePath);
        if (!Files.isRegularFile(p)) {
            throw HttpStatusException.notFound("FILE_MISSING", "The file is no longer in storage");
        }
        return p;
    }

    public void deleteQuietly(String relativePath) {
        if (relativePath == null) return;
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete stored file {}: {}", relativePath, e.getMessage());
        }
    }

    // ----------------------------------------------------------------

    private Path resolve(String relativePath) {
        Path p = baseDir.resolve(relativePath).normalize();
        if (!p.startsWith(baseDir)) {
            throw new IllegalArgumentException("Path escapes the storage directory");
        }
        return p;
    }

    private Path computeBaseDir() {
        if (props.getMode() == StorageProperties.Mode.NETWORK) {
            StorageProperties.Network n = props.getNetwork();
            if (n.getMountPath() == null || n.getMountPath().isBlank()) {
                throw new IllegalStateException("STORAGE_MODE=NETWORK needs STORAGE_NETWORK_MOUNT_PATH");
            }
            Path mount = Paths.get(n.getMountPath()).toAbsolutePath().normalize();
            String dir = n.getDirectory() == null ? "" : n.getDirectory();
            return mount.resolve(dir).normalize();
        }
        if (props.getRoot() == null || props.getRoot().isBlank()) {
            throw new IllegalStateException("STORAGE_MODE=LOCAL needs STORAGE_ROOT");
        }
        return Paths.get(props.getRoot()).toAbsolutePath().normalize();
    }

    private void ensureAvailable() {
        if (props.getMode() == StorageProperties.Mode.NETWORK && props.getNetwork().isRequireMount()) {
            long now = System.currentTimeMillis();
            if (now - lastMountCheckAt > MOUNT_CHECK_CACHE_MS) {
                lastMountOk = isMounted(Paths.get(props.getNetwork().getMountPath()).toAbsolutePath().normalize());
                lastMountCheckAt = now;
            }
            if (!lastMountOk) {
                throw HttpStatusException.storageUnavailable("Network storage is not mounted");
            }
        }
    }

    private void ensureMounted() {
        Path mount = Paths.get(props.getNetwork().getMountPath()).toAbsolutePath().normalize();
        if (!isMounted(mount)) {
            throw new IllegalStateException("STORAGE_NETWORK_REQUIRE_MOUNT=true but " + mount
                    + " is not on a mounted network share. Mount the "
                    + props.getNetwork().getProtocol() + " share first, or set STORAGE_NETWORK_REQUIRE_MOUNT=false.");
        }
    }

    private static final java.util.Set<String> NETWORK_FS = java.util.Set.of(
            "cifs", "smb3", "smbfs", "nfs", "nfs4", "9p", "ceph", "glusterfs",
            "fuse.sshfs", "fuse.rclone", "fuse.davfs", "davfs", "fuse.glusterfs");

    /**
     * True when the file system that holds this path is a network file
     * system. Reads /proc/self/mountinfo and finds the most specific mount
     * containing the path, so a local folder on a separate /home partition
     * does not pass for a share. Verifies on Linux only; elsewhere it falls
     * back to "the directory exists" and says so in the log.
     */
    private boolean isMounted(Path path) {
        Path info = Paths.get("/proc/self/mountinfo");
        if (!Files.isReadable(info)) {
            log.warn("Cannot verify mounts on this OS, only checking that {} exists", path);
            return Files.isDirectory(path);
        }
        try {
            if (!Files.isDirectory(path)) {
                return false;
            }
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
