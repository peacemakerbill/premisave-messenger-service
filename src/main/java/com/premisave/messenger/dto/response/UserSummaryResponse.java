package com.premisave.messenger.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserSummaryResponse {
    private String id;
    private String username;
    private String email;
    private String firstName;
    private String lastName;
    private String profilePictureUrl;
    private String displayName;

    // Populated in chat contexts (e.g. GET /api/chats) from PresenceService.
    // Null when this DTO is used elsewhere (e.g. message sender preview)
    // and presence wasn't looked up.
    private Boolean online;
    private LocalDateTime lastSeen;
}