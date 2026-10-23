package com.premisave.messenger.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** One person's statuses that are still showing, as listed under New. */
@Data
public class StatusGroupResponse {

    private UserSummaryResponse user;
    private List<StatusResponse> statuses;

    // How many of them the caller has not opened yet
    private int unseenCount;
    private LocalDateTime latestAt;
}