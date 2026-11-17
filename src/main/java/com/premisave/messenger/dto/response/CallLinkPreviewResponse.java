package com.premisave.messenger.dto.response;

import com.premisave.messenger.enums.CallStatus;
import com.premisave.messenger.enums.CallType;
import lombok.Data;

/** What someone sees before joining from a call link. */
@Data
public class CallLinkPreviewResponse {
    private String callId;
    private String title;
    private CallType type;
    private CallStatus status;
    private String hostName;
    private int participantCount;
    private boolean locked;
    private boolean full;
    /** False when the link has expired or the call has ended. */
    private boolean joinable;
}
