package com.premisave.messenger.config;

import com.premisave.messenger.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;

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
                String token = authHeader.substring(7);

                if (jwtService.isTokenValid(token)) {
                    String username = jwtService.extractUsername(token);
                    if (username != null) {
                        accessor.setUser(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                username, null, null));
                        log.info("WebSocket authenticated for user: {}", username);
                    }
                } else {
                    log.warn("Invalid JWT token during WebSocket connection");
                }
            } else {
                log.warn("Missing Authorization header in WebSocket connection");
            }
        }

        return message;
    }
}