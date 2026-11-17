package com.premisave.messenger.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
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

    /**
     * The account type from auth-service (CLIENT, ADMIN and so on), read from /profile/me to tell administrators apart.
     * Write-only: it is read in from auth-service but never sent out in this service's own answers.
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String role;

    // Populated in chat contexts (e.g. GET /api/chats) from PresenceService.
    // Null when this DTO is used elsewhere (e.g. message sender preview)
    // and presence wasn't looked up.
    private Boolean online;
    private LocalDateTime lastSeen;
}