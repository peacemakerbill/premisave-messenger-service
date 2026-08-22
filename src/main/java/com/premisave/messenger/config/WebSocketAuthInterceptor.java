package com.premisave.messenger.config;

import com.premisave.messenger.client.AuthServiceClient;
import com.premisave.messenger.dto.response.UserSummaryResponse;
import com.premisave.messenger.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;
    private final AuthServiceClient authServiceClient;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        // Handle CONNECT frame for authentication
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String rawToken = authHeader.substring(7);

                if (jwtService.isTokenValid(rawToken)) {
                    resolveAndAuthenticate(accessor, authHeader);
                } else {
                    log.warn("Invalid JWT token during WebSocket connection");
                }
            } else {
                log.warn("Missing Authorization header in WebSocket connection");
            }
        }

        return message;
    }

    /**
     * Resolves the REAL user ID via auth-service (not the JWT's "sub"
     * claim, which is the user's email) so that Principal.getName()
     * is consistent with the real userId used everywhere else in the
     * app: Chat.participantIds, MessageService's recipientId in
     * convertAndSendToUser(), and PresenceService's userId key.
     *
     * Without this, message routing via convertAndSendToUser(realId, ...)
     * and presence lookups by real ID would never match a session whose
     * Principal was keyed by email instead.
     *
     * Also stashes the full "Bearer <jwt>" string in the STOMP session
     * attributes, so later frames (e.g. chat.sendMessage) can reuse the
     * real token for their own downstream Feign calls instead of
     * fabricating a fake one.
     */
    private void resolveAndAuthenticate(StompHeaderAccessor accessor, String fullAuthHeader) {
        try {
            UserSummaryResponse currentUser = authServiceClient.getCurrentUser(fullAuthHeader);

            if (currentUser == null || currentUser.getId() == null) {
                log.warn("Could not resolve real user ID during WebSocket connect - auth-service returned no user");
                return;
            }

            accessor.setUser(new UsernamePasswordAuthenticationToken(currentUser.getId(), null, null));

            if (accessor.getSessionAttributes() != null) {
                accessor.getSessionAttributes().put("jwt", fullAuthHeader);
            } else {
                log.warn("STOMP session attributes were null - could not stash JWT for user {}", currentUser.getId());
            }

            log.info("WebSocket authenticated for user: {} ({})", currentUser.getId(), currentUser.getEmail());
        } catch (Exception e) {
            log.warn("Failed to resolve user via auth-service during WebSocket connect: {}", e.getMessage());
        }
    }
}