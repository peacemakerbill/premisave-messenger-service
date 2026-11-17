package com.premisave.messenger.dto.response;

import com.premisave.messenger.enums.CallEventType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** A live push on /user/queue/calls. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CallEvent {

    private CallEventType type;
    private String callId;
    /** Who did it. */
    private String actorId;
    /** Who it is about, when that is somebody other than the actor. */
    private String userId;
    /** The call as it is now. */
    private CallResponse call;
    /** The message, for CHAT. */
    private CallChatResponse message;
    private LocalDateTime at;
}
