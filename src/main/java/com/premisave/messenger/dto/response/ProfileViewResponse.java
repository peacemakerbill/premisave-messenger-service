package com.premisave.messenger.dto.response;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ProfileViewResponse {
    private String id;
    private String viewerId;
    private String viewerName;
    private String viewerProfilePictureUrl;
    private String targetId;
    private LocalDateTime viewedAt;
    private String source;
    private String deviceType;
    private String message;
}