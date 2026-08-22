package com.premisave.messenger.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        // User profiles cache - 30 minutes
        cacheConfigurations.put("users", RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(30)));

        // Chat access cache - 5 minutes (stricter for access control)
        cacheConfigurations.put("chat-access", RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(5)));

        // Group data cache - 10 minutes
        cacheConfigurations.put("group-data", RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10)));

        // Chat details cache - 15 minutes
        cacheConfigurations.put("chat-details", RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(15)));

        // Message reactions cache - 5 minutes
        cacheConfigurations.put("message-reactions", RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(5)));

        return RedisCacheManager.builder(connectionFactory)
            .withInitialCacheConfigurations(cacheConfigurations)
            .build();
    }
}