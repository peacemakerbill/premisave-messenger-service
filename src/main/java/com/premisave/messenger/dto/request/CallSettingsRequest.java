package com.premisave.messenger.dto.request;

import lombok.Data;

@Data
public class CallSettingsRequest {

    /** True turns call recording on for everyone, false turns it off. */
    private boolean enabled;
}
