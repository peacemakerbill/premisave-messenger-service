package com.premisave.messenger.storage;

/** Where files are kept: a folder on this server, or a network share that the operating system has already mounted. */
public enum StorageMode {
    LOCAL, NETWORK;

    /** Reads "LOCAL" or "NETWORK" in any case. Throws a plain message for anything else. */
    public static StorageMode parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Choose LOCAL or NETWORK");
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The storage mode must be LOCAL or NETWORK");
        }
    }
}
