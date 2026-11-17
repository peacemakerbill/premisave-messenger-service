package com.premisave.messenger.dto.websocket;

import lombok.Data;

/** What the app sends over STOMP about a call, such as "my phone is ringing". */
@Data
public class CallSignalMessage {
    private String callId;
}
