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

    // The person who wrote the review, resolved by the messenger service so the
    // client gets a name, email and picture instead of a bare id or email.
    private UserSummaryResponse reviewer;
}