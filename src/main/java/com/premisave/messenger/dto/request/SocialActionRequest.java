package com.premisave.messenger.dto.request;

import lombok.Data;

@Data
public class SocialActionRequest {
    private String targetId;
    private String reviewId;
    private Integer rating;
    private String comment;
}