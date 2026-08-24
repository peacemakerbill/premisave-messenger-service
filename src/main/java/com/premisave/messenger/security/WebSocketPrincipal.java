package com.premisave.messenger.security;

import java.security.Principal;

/**
 * Carries the real userId AND the raw "Bearer &lt;jwt&gt;" token together as
 * a single Principal, so both travel through Spring's STOMP session using
 * the exact same propagation mechanism (Principal set on the WebSocket
 * session during CONNECT, then injected into every later @MessageMapping
 * method) - already proven reliable, since senderId (from getName())
 * comes through correctly on every SEND.
 *
 * getName() returns the userId so every existing call site
 * (principal.getName()) keeps working completely unchanged. getToken()
 * is the new addition, used only where a downstream Feign call needs the
 * real JWT (e.g. sender enrichment in MessageService).
 */
public class WebSocketPrincipal implements Principal {

    private final String userId;
    private final String token;

    public WebSocketPrincipal(String userId, String token) {
        this.userId = userId;
        this.token = token;
    }

    @Override
    public String getName() {
        return userId;
    }

    public String getToken() {
        return token;
    }
}