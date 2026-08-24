package com.premisave.messenger.realtime;

import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Runs on every messenger-service instance. Every instance receives every
 * published envelope (Redis pub/sub is fan-out to all subscribers), and
 * each attempts local delivery via SimpMessagingTemplate. Spring's local
 * broker silently no-ops if this instance doesn't have that user's
 * session connected - only the instance actually holding their WebSocket
 * connection succeeds. This is intentional and is what makes delivery
 * work correctly regardless of which instance a user's session lands on.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisMessageSubscriber implements MessageListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String json = new String(message.getBody(), StandardCharsets.UTF_8);
            WsRelayEnvelope envelope = objectMapper.readValue(json, WsRelayEnvelope.class);
            if (envelope.getUserId() != null) {
                messagingTemplate.convertAndSendToUser(
                        envelope.getUserId(), envelope.getDestination(), envelope.getPayload());
            } else {
                messagingTemplate.convertAndSend(envelope.getDestination(), envelope.getPayload());
            }
        } catch (Exception e) {
            log.error("Failed to process WebSocket relay message: {}", e.getMessage(), e);
        }
    }
}