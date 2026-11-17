package com.premisave.messenger.dto.response;

import java.util.List;

/** Everything the administrators' storage screen shows. */
public record StorageSettingsResponse(
        List<String> allowedRoots,
        Attachments attachments,
        Recordings recordings) {

    /**
     * @param source   "DASHBOARD" when an administrator saved the values, "ENV" while the .env values apply
     * @param location the folder files are stored in right now, as the service sees it
     * @param env      the values in .env, which "Reset to .env" goes back to
     */
    public record Attachments(
            String source,
            String mode,
            String root,
            String networkMountPath,
            String networkDirectory,
            boolean networkRequireMount,
            String location,
            boolean ready,
            String problem,
            Long freeMb,
            Env env) {
    }

    public record Env(String mode, String root, String networkMountPath, String networkDirectory, boolean networkRequireMount) {
    }

    /**
     * @param sharedFolder the folder the recorder writes to, from .env. It is fixed by the recorder's Docker mount
     * @param subfolder    the folder inside it that new recordings go to. Empty means the shared folder itself
     */
    public record Recordings(
            String source,
            String mode,
            String sharedFolder,
            String subfolder,
            int minFreeMb,
            int envMinFreeMb,
            String location,
            boolean ready,
            String problem,
            Long freeMb) {
    }
}
