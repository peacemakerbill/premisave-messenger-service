package com.premisave.messenger.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** What the client needs to build the status composer. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatusSettingsResponse {

    private int maxHours;
    private int defaultHours;
    private int textMaxLength;
    private int commentMaxLength;
    private boolean pastVisibleToFollowers;
}