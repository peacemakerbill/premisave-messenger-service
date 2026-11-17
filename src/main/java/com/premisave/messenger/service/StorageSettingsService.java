package com.premisave.messenger.service;

import com.premisave.messenger.config.CallProperties;
import com.premisave.messenger.config.StorageProperties;
import com.premisave.messenger.dto.request.StorageAttachmentsRequest;
import com.premisave.messenger.dto.response.StorageCheckResponse;
import com.premisave.messenger.entity.StorageSettings;
import com.premisave.messenger.exception.HttpStatusException;
import com.premisave.messenger.repository.StorageSettingsRepository;
import com.premisave.messenger.storage.MountCheck;
import com.premisave.messenger.storage.StorageLocation;
import com.premisave.messenger.storage.StorageMode;
import com.premisave.messenger.storage.StoragePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Where files are stored: the .env values until an administrator saves something from the dashboard, and from then on the
 * dashboard value, until it is reset.
 *
 * Folders can only be chosen inside the allowed roots (STORAGE_ALLOWED_ROOTS), and every change is tried before it is saved:
 * the folder is created, written to and, for a network share, checked to be really mounted. Folders that were used before a
 * change are remembered, so files saved earlier are still found. The settings are cached for a few seconds, because every
 * upload and download asks for them.
 */
@Service
public class StorageSettingsService {

    private static final Logger log = LoggerFactory.getLogger(StorageSettingsService.class);

    private static final long CACHE_MILLIS = 5_000;
    private static final int MAX_PREVIOUS_FOLDERS = 20;
    private static final int MAX_MIN_FREE_MB = 10_000_000;

    private final StorageSettingsRepository repository;
    private final StorageProperties props;
    private final CallProperties callProps;

    private volatile StorageSettings cached;
    private volatile long cachedAt;

    public StorageSettingsService(StorageSettingsRepository repository, StorageProperties props, CallProperties callProps) {
        this.repository = repository;
        this.props = props;
        this.callProps = callProps;
    }

    // ================================================================ what applies now

    /** The saved settings, cached for a few seconds. Empty (nothing saved) when there is no record yet. */
    public StorageSettings settings() {
        long now = System.currentTimeMillis();
        StorageSettings s = cached;
        if (s != null && now - cachedAt < CACHE_MILLIS) return s;
        s = repository.findById(StorageSettings.ID).orElseGet(StorageSettings::new);
        cached = s;
        cachedAt = now;
        return s;
    }

    private void forget() {
        cached = null;
        cachedAt = 0;
    }

    /** The folders the administrators may choose from. .env lists them, and by default they are the .env folders themselves. */
    public List<Path> allowedRoots() {
        List<Path> roots = StoragePaths.parseRoots(props.getAllowedRoots());
        if (!roots.isEmpty()) return roots;
        List<Path> fallback = new ArrayList<>();
        addIfPresent(fallback, props.getRoot());
        addIfPresent(fallback, props.getNetwork().getMountPath());
        return fallback;
    }

    private static void addIfPresent(List<Path> out, String value) {
        if (value == null || value.isBlank()) return;
        Path p = java.nio.file.Paths.get(value.trim()).toAbsolutePath().normalize();
        if (!out.contains(p)) out.add(p);
    }

    /** The attachment storage that the .env values describe. */
    public StorageLocation environmentLocation() {
        StorageMode mode = props.getMode() == StorageProperties.Mode.NETWORK ? StorageMode.NETWORK : StorageMode.LOCAL;
        StorageProperties.Network n = props.getNetwork();
        return build(mode, props.getRoot(), n.getMountPath(), n.getDirectory(), n.isRequireMount(), false, false);
    }

    /**
     * The attachment storage in use now: what an administrator saved, or the .env values. A saved folder that is no longer
     * inside the allowed roots (because .env changed) is ignored with a warning instead of being used.
     */
    public StorageLocation attachmentsLocation() {
        StorageSettings.Attachments saved = settings().getAttachments();
        if (saved == null) return environmentLocation();
        try {
            StorageMode mode = StorageMode.parse(saved.getMode());
            return build(mode, saved.getRoot(), saved.getNetworkMountPath(), saved.getNetworkDirectory(),
                    saved.isNetworkRequireMount(), true, true);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Ignoring the storage folder saved from the dashboard: {}", e.getMessage());
            return environmentLocation();
        }
    }

    /** Folders that held files before they were moved, and the .env folder. They are searched when a file is not in the current one. */
    public List<Path> fallbackBases(Path current) {
        List<Path> out = new ArrayList<>();
        for (String s : settings().getPreviousAttachmentBases()) {
            try {
                Path p = StoragePaths.absolute(s, "A saved folder");
                if (!p.equals(current) && !out.contains(p)) out.add(p);
            } catch (IllegalArgumentException ignored) {
                // a damaged entry is skipped
            }
        }
        try {
            Path env = environmentLocation().base();
            if (!env.equals(current) && !out.contains(env)) out.add(env);
        } catch (IllegalStateException | IllegalArgumentException ignored) {
            // .env describes nothing usable
        }
        return out;
    }

    /** The folder recordings go to inside the recorder's shared folder. Empty means the shared folder itself. */
    public String recordingsSubfolder() {
        StorageSettings.Recordings r = settings().getRecordings();
        if (r == null) return "";
        try {
            return StoragePaths.folderName(r.getSubfolder(), "The recordings folder");
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring the recordings folder saved from the dashboard: {}", e.getMessage());
            return "";
        }
    }

    /** Recording is refused below this many free megabytes. */
    public int recordingsMinFreeMb() {
        StorageSettings.Recordings r = settings().getRecordings();
        return r == null ? callProps.getRecordingsMinFreeMb() : Math.max(0, r.getMinFreeMb());
    }

    public boolean attachmentsFromDashboard() {
        return settings().getAttachments() != null;
    }

    public boolean recordingsFromDashboard() {
        return settings().getRecordings() != null;
    }

    // ================================================================ building a location

    /** Works out the folder for a set of values, and checks the rules (allowed roots, folder names). Writes nothing. */
    private StorageLocation build(StorageMode mode, String root, String mountPath, String directory, boolean requireMount,
                                  boolean fromDashboard, boolean enforceAllowedRoots) {
        List<Path> roots = allowedRoots();
        if (mode == StorageMode.NETWORK) {
            if (mountPath == null || mountPath.isBlank()) {
                throw new IllegalStateException("NETWORK storage needs the mount path of the share");
            }
            Path mount = StoragePaths.absolute(mountPath, "The mount path");
            if (enforceAllowedRoots && !StoragePaths.isInside(mount, roots)) {
                throw new IllegalArgumentException("The mount path " + mount + " is not inside an allowed folder. "
                        + "Add it to STORAGE_ALLOWED_ROOTS in .env first");
            }
            String dir = StoragePaths.folderName(directory, "The folder inside the share");
            Path base = dir.isEmpty() ? mount : mount.resolve(dir).normalize();
            return new StorageLocation(mode, base, mount, requireMount, fromDashboard);
        }
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("LOCAL storage needs a folder");
        }
        Path base = StoragePaths.absolute(root, "The storage folder");
        if (enforceAllowedRoots && !StoragePaths.isInside(base, roots)) {
            throw new IllegalArgumentException("The folder " + base + " is not inside an allowed folder. "
                    + "Add its parent to STORAGE_ALLOWED_ROOTS in .env first");
        }
        return new StorageLocation(mode, base, null, false, fromDashboard);
    }

    // ================================================================ trying and saving

    /** Tries a setting without saving it: the folder is created, written to and, for a share, checked to be mounted. */
    public StorageCheckResponse checkAttachments(StorageAttachmentsRequest request) {
        StorageLocation location;
        try {
            location = fromRequest(request);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new StorageCheckResponse(false, e.getMessage(), null, null);
        }
        return probe(location);
    }

    /** Saves new attachment storage after trying it. The folder in use until now is remembered, so older files are still found. */
    public StorageCheckResponse saveAttachments(StorageAttachmentsRequest request, String adminId) {
        StorageLocation location;
        try {
            location = fromRequest(request);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw HttpStatusException.badRequest(e.getMessage());
        }
        StorageCheckResponse result = probe(location);
        if (!result.ok()) throw HttpStatusException.badRequest(result.problem());

        StorageSettings settings = repository.findById(StorageSettings.ID).orElseGet(StorageSettings::new);
        remember(settings, attachmentsLocation().base(), location.base());

        StorageSettings.Attachments saved = new StorageSettings.Attachments();
        saved.setMode(location.mode().name());
        if (location.mode() == StorageMode.NETWORK) {
            saved.setNetworkMountPath(location.mountPath().toString());
            Path relative = location.mountPath().relativize(location.base());
            saved.setNetworkDirectory(relative.toString());
            saved.setNetworkRequireMount(location.requireMount());
        } else {
            saved.setRoot(location.base().toString());
        }
        settings.setAttachments(saved);
        touch(settings, adminId);
        repository.save(settings);
        forget();
        log.info("Attachment storage set from the dashboard by {}: {} {}", adminId, location.mode(), location.base());
        return result;
    }

    /** Goes back to the .env values. The folder in use until now is remembered. */
    public void resetAttachments(String adminId) {
        StorageSettings settings = repository.findById(StorageSettings.ID).orElseGet(StorageSettings::new);
        if (settings.getAttachments() == null) return;
        remember(settings, attachmentsLocation().base(), environmentLocation().base());
        settings.setAttachments(null);
        touch(settings, adminId);
        repository.save(settings);
        forget();
        log.info("Attachment storage reset to .env by {}", adminId);
    }

    /** Saves the recordings folder and free space limit. The folder was already tried by the caller. */
    public void saveRecordings(String subfolder, int minFreeMb, String adminId) {
        String clean;
        try {
            clean = StoragePaths.folderName(subfolder, "The recordings folder");
        } catch (IllegalArgumentException e) {
            throw HttpStatusException.badRequest(e.getMessage());
        }
        if (minFreeMb < 0 || minFreeMb > MAX_MIN_FREE_MB) {
            throw HttpStatusException.badRequest("The free space limit must be between 0 and " + MAX_MIN_FREE_MB + " MB");
        }
        StorageSettings settings = repository.findById(StorageSettings.ID).orElseGet(StorageSettings::new);
        StorageSettings.Recordings saved = new StorageSettings.Recordings();
        saved.setSubfolder(clean);
        saved.setMinFreeMb(minFreeMb);
        settings.setRecordings(saved);
        touch(settings, adminId);
        repository.save(settings);
        forget();
        log.info("Recordings storage set from the dashboard by {}: folder '{}', at least {} MB free", adminId, clean, minFreeMb);
    }

    public void resetRecordings(String adminId) {
        StorageSettings settings = repository.findById(StorageSettings.ID).orElseGet(StorageSettings::new);
        if (settings.getRecordings() == null) return;
        settings.setRecordings(null);
        touch(settings, adminId);
        repository.save(settings);
        forget();
        log.info("Recordings storage reset to .env by {}", adminId);
    }

    // ================================================================ checks

    /** The state of a folder without changing anything: mounted, readable, writable, and how much is free. */
    public StorageCheckResponse status(StorageLocation location) {
        Path base = location.base();
        try {
            if (location.mustBeMounted() && !MountCheck.isOnNetworkShare(location.mountPath())) {
                return new StorageCheckResponse(false, "The network share is not mounted at " + location.mountPath(),
                        base.toString(), null);
            }
            if (!Files.isDirectory(base)) {
                return new StorageCheckResponse(false, "The folder " + base + " does not exist", base.toString(), null);
            }
            if (!Files.isWritable(base)) {
                return new StorageCheckResponse(false, "The service cannot write to " + base, base.toString(), null);
            }
            return new StorageCheckResponse(true, null, base.toString(), freeMb(base));
        } catch (RuntimeException e) {
            return new StorageCheckResponse(false, "The folder is not usable: " + e.getMessage(), base.toString(), null);
        }
    }

    /** Keeps the folder inside the allowed roots even through links, then creates it, and writes and deletes a small file. */
    private StorageCheckResponse probe(StorageLocation location) {
        Path base = location.base();
        try {
            if (location.mustBeMounted() && !MountCheck.isOnNetworkShare(location.mountPath())) {
                return new StorageCheckResponse(false, "The network share is not mounted at " + location.mountPath()
                        + ". Mount it first, or switch the mount check off", base.toString(), null);
            }
            if (!StoragePaths.isReallyInside(base, allowedRoots())) {
                return new StorageCheckResponse(false, "The folder " + base
                        + " leads outside the allowed folders through a link", base.toString(), null);
            }
            Files.createDirectories(base);
            Path test = base.resolve(".write-test-" + UUID.randomUUID());
            Files.writeString(test, "ok");
            Files.deleteIfExists(test);
            return new StorageCheckResponse(true, null, base.toString(), freeMb(base));
        } catch (IOException | RuntimeException e) {
            return new StorageCheckResponse(false, "The service cannot write to " + base + ": " + e.getMessage(),
                    base.toString(), null);
        }
    }

    private StorageLocation fromRequest(StorageAttachmentsRequest r) {
        if (r == null) throw new IllegalArgumentException("Send the storage settings");
        StorageMode mode = StorageMode.parse(r.mode());
        boolean requireMount = r.networkRequireMount() == null || r.networkRequireMount();
        return build(mode, r.root(), r.networkMountPath(), r.networkDirectory(), requireMount, true, true);
    }

    private static Long freeMb(Path folder) {
        try {
            return Files.getFileStore(folder).getUsableSpace() / (1024 * 1024);
        } catch (IOException e) {
            return null;
        }
    }

    private static void remember(StorageSettings settings, Path leaving, Path arriving) {
        if (leaving == null || leaving.equals(arriving)) return;
        List<String> list = new ArrayList<>(settings.getPreviousAttachmentBases());
        list.remove(leaving.toString());
        list.add(0, leaving.toString());
        list.remove(arriving.toString());
        while (list.size() > MAX_PREVIOUS_FOLDERS) list.remove(list.size() - 1);
        settings.setPreviousAttachmentBases(list);
    }

    private static void touch(StorageSettings settings, String adminId) {
        settings.setUpdatedBy(adminId);
        settings.setUpdatedAt(LocalDateTime.now());
    }
}
