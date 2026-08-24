package com.premisave.messenger.config;

import com.premisave.messenger.realtime.RedisMessagePublisher;
import com.premisave.messenger.realtime.RedisMessageSubscriber;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Subscribes RedisMessageSubscriber to the WebSocket relay channel so
 * every messenger-service instance receives every cross-instance push.
 * See RedisMessagePublisher/RedisMessageSubscriber for why this exists.
 */
@Configuration
@RequiredArgsConstructor
public class RedisMessagingConfig {

    private final RedisConnectionFactory redisConnectionFactory;
    private final RedisMessageSubscriber redisMessageSubscriber;

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer() {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);
        container.addMessageListener(redisMessageSubscriber, new ChannelTopic(RedisMessagePublisher.CHANNEL));
        return container;
    }
}