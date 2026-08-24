package com.premisave.messenger.realtime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Wraps a single "push this payload to this user's STOMP destination"
 * instruction for transport over Redis pub/sub. Every messenger-service
 * instance subscribes to the same channel and republishes locally via
 * SimpMessagingTemplate - so whichever instance actually holds the
 * recipient's live WebSocket session delivers it, regardless of which
 * instance originally handled the request.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WsRelayEnvelope {
    private String userId;
    private String destination;
    private Object payload;
}