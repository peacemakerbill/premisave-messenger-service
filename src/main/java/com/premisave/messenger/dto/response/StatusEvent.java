package com.premisave.messenger.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Pushed on /user/queue/status.
 * NEW_STATUS and STATUS_DELETED go to followers. ACTIVITY goes to the owner with the new totals.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatusEvent {

    private String event;
    private String statusId;
    private String ownerId;

    // ACTIVITY only: the status totals after the change
    private Long views;
    private Long likes;
    private Long reactions;
    private Long comments;

    public static StatusEvent of(String event, String statusId, String ownerId) {
        return new StatusEvent(event, statusId, ownerId, null, null, null, null);
    }
}