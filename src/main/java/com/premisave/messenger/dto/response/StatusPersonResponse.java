package com.premisave.messenger.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Someone who viewed, liked or reacted to a status, and when. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatusPersonResponse {

    private UserSummaryResponse user;

    // Only for reactions
    private String emoji;
    private LocalDateTime at;
}