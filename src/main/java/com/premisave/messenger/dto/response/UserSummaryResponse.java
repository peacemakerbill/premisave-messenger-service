package com.premisave.messenger.dto.response;

import lombok.Data;

@Data
public class UserSummaryResponse {
    private String id;
    private String username;
    private String email;
    private String firstName;
    private String lastName;
    private String profilePictureUrl;
    private String displayName;
}