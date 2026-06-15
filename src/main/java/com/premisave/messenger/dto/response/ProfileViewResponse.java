package com.premisave.messenger.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ProfileViewResponse {

    @JsonProperty("id")
    private String id;

    @JsonProperty("viewerId")
    private String viewerId;

    @JsonProperty("viewerName")
    private String viewerName;

    @JsonProperty("viewerProfilePicture")
    private String viewerProfilePicture;

    @JsonProperty("targetId")
    private String targetId;

    @JsonProperty("viewedAt")
    private LocalDateTime viewedAt;

    @JsonProperty("source")
    private String source;

    @JsonProperty("deviceType")
    private String deviceType;

    @JsonProperty("message")
    private String message;
}