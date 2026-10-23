package com.premisave.messenger.enums;

public enum AttachmentStatus {
    /** Uploaded, not yet sent in a message. Removed by the cleanup job after storage.unclaimed-ttl. */
    UPLOADED,
    /** Bound to exactly one message. */
    ATTACHED,
    /** File removed (message deleted for everyone). Row kept for audit. */
    DELETED
}
