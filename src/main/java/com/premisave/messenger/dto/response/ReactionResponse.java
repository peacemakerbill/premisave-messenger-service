package com.premisave.messenger.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/** The reactions on one message after a change. Also the payload pushed on /user/queue/reactions. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReactionResponse {

    private String messageId;
    private String chatId;

    // Everyone's reactions: emoji -> ids of the users who reacted with it
    private Map<String, List<String>> reactions;

    // Who made this change and the emoji they now have (null when they removed it)
    private String userId;
    private String emoji;
}