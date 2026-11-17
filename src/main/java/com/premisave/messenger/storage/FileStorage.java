package com.premisave.messenger.storage;

import com.premisave.messenger.config.StorageProperties;
import com.premisave.messenger.exception.HttpStatusException;
import com.premisave.messenger.service.StorageSettingsService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * - Where files go is read for every call from StorageSettingsService: the
 *   .env values, or what an administrator saved from the dashboard. A file
 *   that is not in the current folder is looked for in the folders used
 *   before, so moving the storage never loses a file.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileStorage {

    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern PUBLIC_FOLDER = Pattern.compile("^[a-z0-9-]{1,32}$");
    private static final Pattern PUBLIC_EXTENSION = Pattern.compile("^[a-z0-9]{1,5}$");
    private static final long MOUNT_CHECK_CACHE_MS = 10_000L;
    private static final int HEAD_BYTES = 16;

    private final StorageProperties props;
    private final StorageSettingsService settings;

    private volatile Path mountCheckedPath;
    private volatile long lastMountCheckAt;
    private volatile boolean lastMountOk;

    @PostConstruct
    void init() {
        // The .env values must work: fail fast at startup instead of on the first upload. What an administrator saved from
        // the dashboard was tried when it was saved, and is checked again on every use.
        StorageLocation env = settings.environmentLocation();
        if (env.mustBeMounted()) {
            ensureMounted(env);
        }
        Path baseDir = env.base();
        try {
            Files.createDirectories(baseDir);
            if (!Files.isWritable(baseDir)) {
                throw new IllegalStateException("Storage directory is not writable: " + baseDir);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create storage directory " + baseDir, e);
        }
        StorageLocation now = settings.attachmentsLocation();
        log.info("Attachment storage ready: mode={} dir={} source={} maxFileSize={}",
                now.mode(), now.base(), now.fromDashboard() ? "dashboard" : ".env", props.getMaxFileSize());
    }

    /** Private chat attachment: random name, no extension, kept under chats/{chatId}/{yyyy-MM}/. */
    public StoredFile store(InputStream in, String chatId, long maxBytes) {
        if (chatId == null || !SAFE_SEGMENT.matcher(chatId).matches()) {
            throw new IllegalArgumentException("Invalid chat id");
        }
        return write(in, "chats/" + chatId + "/" + YearMonth.now(), UUID.randomUUID().toString(), maxBytes);
    }

    /**
     * Public media (group photos and the like): random name plus a safe
     * extension, kept under public/{folder}/{yyyy-MM}/. Served without a
     * signature, so callers must only pass types that are safe to show.
     */
    public StoredFile storePublic(InputStream in, String folder, String extension, long maxBytes) {
        if (folder == null || !PUBLIC_FOLDER.matcher(folder).matches()) {
            throw new IllegalArgumentException("Invalid media folder");
        }
        if (extension == null || !PUBLIC_EXTENSION.matcher(extension).matches()) {
            throw new IllegalArgumentException("Invalid file extension");
        }
        return write(in, "public/" + folder + "/" + YearMonth.now(), UUID.randomUUID() + "." + extension, maxBytes);
    }

    private StoredFile write(InputStream in, String relativeDir, String fileName, long maxBytes) {
        StorageLocation location = settings.attachmentsLocation();
        ensureAvailable(location);

        Path dir = resolve(location.base(), relativeDir);
        Path tmp = dir.resolve(".upload-" + UUID.randomUUID() + ".tmp");
        Path target = dir.resolve(fileName);

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
            return new StoredFile(relativeDir + "/" + fileName, total, HexFormat.of().formatHex(digest.digest()), head);

        } catch (IOException e) {
            log.error("Failed to write file to {}: {}", dir, e.getMessage());
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

    /**
     * Resolves a stored path for reading. Looks in the current folder first, then in the folders used before. Throws if the
     * file is nowhere.
     */
    public Path open(String relativePath) {
        StorageLocation location = settings.attachmentsLocation();
        HttpStatusException unavailable = null;
        try {
            ensureAvailable(location);
            Path p = resolve(location.base(), relativePath);
            if (Files.isRegularFile(p)) return p;
        } catch (HttpStatusException e) {
            // The current storage is down. The file may still be in a folder used before.
            unavailable = e;
        }
        for (Path old : settings.fallbackBases(location.base())) {
            Path p = resolve(old, relativePath);
            if (Files.isRegularFile(p)) return p;
        }
        if (unavailable != null) throw unavailable;
        throw HttpStatusException.notFound("FILE_MISSING", "The file is no longer in storage");
    }

    public void deleteQuietly(String relativePath) {
        if (relativePath == null) return;
        try {
            StorageLocation location = settings.attachmentsLocation();
            if (Files.deleteIfExists(resolve(location.base(), relativePath))) return;
            for (Path old : settings.fallbackBases(location.base())) {
                if (Files.deleteIfExists(resolve(old, relativePath))) return;
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete stored file {}: {}", relativePath, e.getMessage());
        }
    }

    // ----------------------------------------------------------------

    private static Path resolve(Path baseDir, String relativePath) {
        Path p = baseDir.resolve(relativePath).normalize();
        if (!p.startsWith(baseDir)) {
            throw new IllegalArgumentException("Path escapes the storage directory");
        }
        return p;
    }

    private void ensureAvailable(StorageLocation location) {
        if (location.mustBeMounted()) {
            long now = System.currentTimeMillis();
            if (!location.mountPath().equals(mountCheckedPath) || now - lastMountCheckAt > MOUNT_CHECK_CACHE_MS) {
                lastMountOk = MountCheck.isOnNetworkShare(location.mountPath());
                mountCheckedPath = location.mountPath();
                lastMountCheckAt = now;
            }
            if (!lastMountOk) {
                throw HttpStatusException.storageUnavailable("Network storage is not mounted");
            }
        }
    }

    private void ensureMounted(StorageLocation location) {
        if (!MountCheck.isOnNetworkShare(location.mountPath())) {
            throw new IllegalStateException("STORAGE_NETWORK_REQUIRE_MOUNT=true but " + location.mountPath()
                    + " is not on a mounted network share. Mount the "
                    + props.getNetwork().getProtocol() + " share first, or set STORAGE_NETWORK_REQUIRE_MOUNT=false.");
        }
    }
}
