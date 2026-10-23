package com.premisave.messenger.dto.response;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ReviewResponse {
    private String id;

    // Real id of the reviewer, as returned by the auth-service.
    private String userId;

    // Reviewer details sent by the auth-service with every review. All three are
    // null when the reviewer's account was deleted.
    private String reviewerName;
    private String reviewerEmail;
    private String reviewerProfilePicture;

    private String targetId;
    private Integer rating;
    private String comment;
    private LocalDateTime createdAt;

    // Differs from createdAt when the review was edited.
    private LocalDateTime updatedAt;

    // The reviewer in the same shape as every other user in this API, built from
    // the fields above so the client can open the profile. Null when unknown.
    private UserSummaryResponse reviewer;
}