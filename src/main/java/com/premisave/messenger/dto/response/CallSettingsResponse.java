package com.premisave.messenger.dto.response;

import lombok.Data;

/** The call switches as the signed-in person sees them. */
@Data
public class CallSettingsResponse {

    /** Whether recording is switched on. When false the app hides recording completely. */
    private boolean recordingEnabled;

    /** Whether the person is an administrator and may change the switches. */
    private boolean canManage;

    /** Where recordings are kept: LOCAL (a folder on the server) or NETWORK (a mounted share). */
    private String storageMode;

    /** Whether the storage can take a new recording right now. When false, starting a recording is refused. */
    private boolean storageReady;

    /** Administrators only: the folder the videos are in, as the service sees it. */
    private String storageLocation;

    /** Administrators only: why the storage is not ready (share not mounted, disk full, folder missing). Null when it is. */
    private String storageProblem;

    /** Administrators only: free space on the storage, in megabytes. */
    private Long storageFreeMb;
}
