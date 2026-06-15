package com.premisave.messenger.dto.response;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class WhoIViewedResponse {
    private String id;
    private String fullName;
    private String profilePictureUrl;
    private String username;
    private LocalDateTime viewedAt;
    private String deviceType;
    private String source;
}