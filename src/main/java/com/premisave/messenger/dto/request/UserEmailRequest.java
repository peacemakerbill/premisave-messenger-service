package com.premisave.messenger.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** What the messenger sends to auth-service to have it email one user. The field names match auth-service's InternalEmailRequest. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserEmailRequest {

    private String userId;
    private String subject;
    private String title;
    private String message;
    private Map<String, String> details;
    private String buttonLabel;
    private String buttonPath;
    /** One character shown in the email's round badge, for example "@". Empty means the default. */
    private String icon;
}
