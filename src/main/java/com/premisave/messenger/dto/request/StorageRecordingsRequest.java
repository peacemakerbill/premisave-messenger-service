package com.premisave.messenger.dto.request;

/** The recordings folder inside the recorder's shared folder, and the free space below which recording is refused. */
public record StorageRecordingsRequest(String subfolder, Integer minFreeMb) {
}
