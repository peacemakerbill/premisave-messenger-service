package com.premisave.messenger.dto.request;

import lombok.Data;

@Data
public class ReactionRequest {

    // The emoji to react with, for example a thumbs up. One reaction per person per message.
    private String emoji;
}