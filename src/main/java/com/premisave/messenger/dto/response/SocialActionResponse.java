package com.premisave.messenger.dto.response;

import lombok.Data;

@Data
public class SocialActionResponse {
    private String action;
    private String message;
    private boolean success;
}