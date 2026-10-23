package com.premisave.messenger.dto.response;

import com.premisave.messenger.enums.StatusType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class StatusResponse {

    private String id;
    private String ownerId;

    // Filled when one status is requested on its own
    private UserSummaryResponse owner;

    private StatusType type;
    private String text;
    private String backgroundColor;
    private String caption;

    private String attachmentId;
    private String mediaUrl;
    private String fileName;
    private String mimeType;
    private Long fileSize;

    private int durationHours;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    // Seconds until it disappears, 0 once it has. Lets a client count down without trusting its own clock or time zone.
    private long remainingSeconds;

    // True while the status is still showing, false once it has moved to past statuses
    private boolean active;
    private boolean mine;

    // What the caller has done with it
    private boolean viewedByMe;
    private boolean likedByMe;
    private String myReaction;

    // Totals. Only the owner sees them.
    private Stats stats;

    @Data
    public static class Stats {
        private long views;
        private long likes;
        private long reactions;
        private long comments;
    }
}