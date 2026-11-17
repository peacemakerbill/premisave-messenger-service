package com.premisave.messenger.storage;

import java.nio.file.Path;

/**
 * Where attachments are stored right now, worked out from the .env values or from what an administrator saved.
 *
 * @param mode         LOCAL or NETWORK
 * @param base         the folder every stored file lives under, as this service sees it
 * @param mountPath    NETWORK only: where the share is mounted. Null in LOCAL mode
 * @param requireMount NETWORK only: refuse to use the folder unless the mount path really is a network share
 * @param fromDashboard true when an administrator saved these values, false when they come from .env
 */
public record StorageLocation(StorageMode mode, Path base, Path mountPath, boolean requireMount, boolean fromDashboard) {

    public boolean mustBeMounted() {
        return mode == StorageMode.NETWORK && requireMount && mountPath != null;
    }
}
