package com.premisave.messenger.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class StatusCommentResponse {

    private String id;
    private String statusId;
    private UserSummaryResponse author;
    private String content;
    private LocalDateTime createdAt;

    // Written by the caller
    private boolean mine;

    // The caller wrote it, or owns the status, so they can delete it
    private boolean canDelete;
}