package com.premisave.messenger.realtime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Wraps a single WebSocket push for transport over Redis pub/sub - either
 * targeted at one user (userId set) or a topic-wide broadcast (userId
 * null, e.g. presence changes on /topic/presence). Every messenger-service
 * instance subscribes to the same channel and republishes locally via
 * SimpMessagingTemplate - so whichever instance actually holds the
 * relevant session(s) delivers it, regardless of which instance
 * originally handled the request.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WsRelayEnvelope {
    private String userId;
    private String destination;
    private Object payload;
}