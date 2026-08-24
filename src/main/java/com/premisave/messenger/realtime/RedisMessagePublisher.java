package com.premisave.messenger.realtime;

import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Drop-in replacement for calling SimpMessagingTemplate.convertAndSendToUser
 * directly. Instead of delivering only to sessions on this JVM, it publishes
 * to a shared Redis channel that every messenger-service instance listens
 * on (see RedisMessageSubscriber). This is what makes real-time delivery
 * work correctly when messenger-service is scaled to multiple instances
 * behind a load balancer - without it, a message would only reach the
 * recipient if their WebSocket session happened to land on the same
 * instance that processed the send, which a load balancer never guarantees.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisMessagePublisher {

    public static final String CHANNEL = "messenger:ws-relay";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Same signature as SimpMessagingTemplate.convertAndSendToUser(userId,
     * destination, payload) - existing call sites just swap which bean
     * they call.
     */
    public void convertAndSendToUser(String userId, String destination, Object payload) {
        try {
            WsRelayEnvelope envelope = new WsRelayEnvelope(userId, destination, payload);
            String json = objectMapper.writeValueAsString(envelope);
            redisTemplate.convertAndSend(CHANNEL, json);
        } catch (Exception e) {
            log.error("Failed to publish WebSocket relay message for user {} on {}: {}",
                    userId, destination, e.getMessage(), e);
        }
    }

    /**
     * Same signature as SimpMessagingTemplate.convertAndSend(destination,
     * payload) - for topic-wide broadcasts (e.g. /topic/presence) rather
     * than a single targeted user. userId is left null in the envelope to
     * signal this to RedisMessageSubscriber.
     */
    public void convertAndSend(String destination, Object payload) {
        try {
            WsRelayEnvelope envelope = new WsRelayEnvelope(null, destination, payload);
            String json = objectMapper.writeValueAsString(envelope);
            redisTemplate.convertAndSend(CHANNEL, json);
        } catch (Exception e) {
            log.error("Failed to publish WebSocket broadcast on {}: {}", destination, e.getMessage(), e);
        }
    }
}