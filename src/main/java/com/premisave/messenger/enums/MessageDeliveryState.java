package com.premisave.messenger.enums;

public enum MessageDeliveryState {
    PENDING,                    // Waiting to broadcast
    DELIVERED_TO_DB,            // Persisted successfully
    FAILED_TO_NOTIFY,           // DB OK but notification failed
    NOTIFIED_ALL,               // All recipients notified
    PARTIALLY_NOTIFIED,         // Some recipients got it
    FAILED                      // Complete failure
}