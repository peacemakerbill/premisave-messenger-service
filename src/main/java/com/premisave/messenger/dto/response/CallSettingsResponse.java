package com.premisave.messenger.dto.response;

import lombok.Data;

/** The call switches as the signed-in person sees them. */
@Data
public class CallSettingsResponse {

    /** Whether recording is switched on. When false the app hides recording completely. */
    private boolean recordingEnabled;

    /** Whether the person is an administrator and may change the switches. */
    private boolean canManage;
}
