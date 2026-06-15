package com.premisave.messenger.dto.response;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ReviewResponse {
    private String id;
    private String userId;
    private String targetId;
    private Integer rating;
    private String comment;
    private LocalDateTime createdAt;
}