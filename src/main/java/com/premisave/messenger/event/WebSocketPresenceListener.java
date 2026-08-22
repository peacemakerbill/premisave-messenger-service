package com.premisave.messenger.event;

import com.premisave.messenger.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;

/**
 * Wires PresenceService (userOnline/userOffline) to actual WebSocket
 * session lifecycle events. Before this listener existed, PresenceService
 * had the correct logic but nothing ever called it - every user always
 * showed offline regardless of connection state.
 *
 * Relies on WebSocketAuthInterceptor having set the STOMP Principal to
 * the real user ID (not email) during CONNECT, so this stays consistent
 * with how presence is looked up elsewhere (ChatService, by real ID).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketPresenceListener {

    private final PresenceService presenceService;

    @EventListener
    public void handleSessionConnected(SessionConnectedEvent event) {
        Principal user = extractPrincipal(event.getMessage());
        if (user != null && user.getName() != null) {
            presenceService.userOnline(user.getName());
            log.info("User {} connected via WebSocket - marked online", user.getName());
        } else {
            log.debug("WebSocket session connected without a resolved principal - skipping presence update");
        }
    }

    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        Principal user = extractPrincipal(event.getMessage());
        if (user != null && user.getName() != null) {
            presenceService.userOffline(user.getName());
            log.info("User {} disconnected from WebSocket - marked offline", user.getName());
        } else {
            log.debug("WebSocket session disconnected without a resolved principal - skipping presence update");
        }
    }

    private Principal extractPrincipal(Message<byte[]> message) {
        return StompHeaderAccessor.wrap(message).getUser();
    }
}