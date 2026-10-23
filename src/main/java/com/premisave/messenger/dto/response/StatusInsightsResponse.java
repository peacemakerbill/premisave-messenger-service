package com.premisave.messenger.dto.response;

import lombok.Data;

import java.util.List;
import java.util.Map;

/** Everything the owner can see about one status: who viewed, liked and reacted, and the comments. */
@Data
public class StatusInsightsResponse {

    private StatusResponse status;
    private List<StatusPersonResponse> viewers;
    private List<StatusPersonResponse> likers;
    private List<StatusPersonResponse> reactors;

    // Reactions by emoji, for example {"🔥": 4, "👍": 2}
    private Map<String, Long> reactionBreakdown;

    private List<StatusCommentResponse> comments;
}