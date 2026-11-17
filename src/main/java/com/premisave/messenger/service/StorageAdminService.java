package com.premisave.messenger.service;

import com.premisave.messenger.config.CallProperties;
import com.premisave.messenger.config.StorageProperties;
import com.premisave.messenger.dto.request.StorageRecordingsRequest;
import com.premisave.messenger.dto.response.StorageCheckResponse;
import com.premisave.messenger.dto.response.StorageSettingsResponse;
import com.premisave.messenger.entity.StorageSettings;
import com.premisave.messenger.exception.HttpStatusException;
import com.premisave.messenger.storage.RecordingStorage;
import com.premisave.messenger.storage.StorageLocation;
import com.premisave.messenger.storage.StorageMode;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * The administrators' storage screen: what is in use now, where it comes from (.env or the dashboard), whether it works, and
 * the changes an administrator can make. The rules themselves are in {@link StorageSettingsService}.
 */
@Service
public class StorageAdminService {

    private final StorageSettingsService settings;
    private final RecordingStorage recordingStorage;
    private final StorageProperties props;
    private final CallProperties callProps;

    public StorageAdminService(StorageSettingsService settings, RecordingStorage recordingStorage,
                               StorageProperties props, CallProperties callProps) {
        this.settings = settings;
        this.recordingStorage = recordingStorage;
        this.props = props;
        this.callProps = callProps;
    }

    public StorageSettingsResponse view() {
        return new StorageSettingsResponse(
                settings.allowedRoots().stream().map(Path::toString).toList(),
                attachmentsView(),
                recordingsView());
    }

    private StorageSettingsResponse.Attachments attachmentsView() {
        StorageLocation location = settings.attachmentsLocation();
        StorageCheckResponse status = settings.status(location);
        StorageSettingsResponse.Env env = new StorageSettingsResponse.Env(
                props.getMode().name(),
                nullToEmpty(props.getRoot()),
                nullToEmpty(props.getNetwork().getMountPath()),
                nullToEmpty(props.getNetwork().getDirectory()),
                props.getNetwork().isRequireMount());

        StorageSettings.Attachments saved = settings.settings().getAttachments();
        boolean dashboard = location.fromDashboard() && saved != null;
        return new StorageSettingsResponse.Attachments(
                dashboard ? "DASHBOARD" : "ENV",
                location.mode().name(),
                dashboard ? nullToEmpty(saved.getRoot()) : env.root(),
                dashboard ? nullToEmpty(saved.getNetworkMountPath()) : env.networkMountPath(),
                dashboard ? nullToEmpty(saved.getNetworkDirectory()) : env.networkDirectory(),
                dashboard ? saved.isNetworkRequireMount() : env.networkRequireMount(),
                location.base().toString(),
                status.ok(),
                status.problem(),
                status.freeMb(),
                env);
    }

    private StorageSettingsResponse.Recordings recordingsView() {
        RecordingStorage.Status status = recordingStorage.status();
        String shared;
        try {
            shared = recordingStorage.root().toString();
        } catch (IllegalStateException e) {
            shared = "";
        }
        return new StorageSettingsResponse.Recordings(
                settings.recordingsFromDashboard() ? "DASHBOARD" : "ENV",
                callProps.getRecordingsMode() == CallProperties.StorageMode.NETWORK ? StorageMode.NETWORK.name() : StorageMode.LOCAL.name(),
                shared,
                settings.recordingsSubfolder(),
                settings.recordingsMinFreeMb(),
                callProps.getRecordingsMinFreeMb(),
                recordingStorage.location(),
                status.ready(),
                status.problem(),
                status.freeMb());
    }

    // ============================================================ recordings

    public StorageCheckResponse checkRecordings(StorageRecordingsRequest request) {
        StorageCheckResponse folder = recordingStorage.tryFolder(request == null ? "" : request.subfolder());
        if (!folder.ok()) return folder;
        String limit = limitProblem(request == null ? null : request.minFreeMb());
        return limit == null ? folder : new StorageCheckResponse(false, limit, folder.location(), folder.freeMb());
    }

    public StorageSettingsResponse saveRecordings(StorageRecordingsRequest request, String adminId) {
        StorageCheckResponse check = checkRecordings(request);
        if (!check.ok()) throw HttpStatusException.badRequest(check.problem());
        int min = request.minFreeMb() == null ? settings.recordingsMinFreeMb() : request.minFreeMb();
        settings.saveRecordings(request.subfolder(), min, adminId);
        recordingStorage.refresh();
        return view();
    }

    public StorageSettingsResponse resetRecordings(String adminId) {
        settings.resetRecordings(adminId);
        recordingStorage.refresh();
        return view();
    }

    private static String limitProblem(Integer minFreeMb) {
        if (minFreeMb == null) return null;
        if (minFreeMb < 0 || minFreeMb > 10_000_000) return "The free space limit must be between 0 and 10000000 MB";
        return null;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
