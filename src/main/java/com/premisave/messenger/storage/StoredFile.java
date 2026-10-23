package com.premisave.messenger.storage;

/** Result of a successful write: where it landed and what was written. */
public record StoredFile(String relativePath, long size, String sha256, byte[] head) {
}
