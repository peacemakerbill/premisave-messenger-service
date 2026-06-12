package com.premisave.messenger.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Configuration
public class RateLimiterConfig {

    @Value("${rate-limit.requests-per-minute:60}")
    private int requestsPerMinute;

    @Bean
    public RateLimiterBucket rateLimiterBucket() {
        return new RateLimiterBucket(requestsPerMinute);
    }

    /**
     * Simple fixed-window in-memory rate limiter.
     * Allows up to `capacity` requests per 1-minute window, then resets.
     */
    public static class RateLimiterBucket {

        private final int capacity;
        private final AtomicInteger tokens;
        private final AtomicLong windowStart;
        private static final long WINDOW_MILLIS = 60_000L;

        public RateLimiterBucket(int capacity) {
            this.capacity = capacity;
            this.tokens = new AtomicInteger(capacity);
            this.windowStart = new AtomicLong(System.currentTimeMillis());
        }

        /**
         * Attempts to consume one token. Returns true if allowed, false if rate limited.
         */
        public synchronized boolean tryConsume() {
            long now = System.currentTimeMillis();
            if (now - windowStart.get() >= WINDOW_MILLIS) {
                windowStart.set(now);
                tokens.set(capacity);
            }
            if (tokens.get() > 0) {
                tokens.decrementAndGet();
                return true;
            }
            return false;
        }
    }
}