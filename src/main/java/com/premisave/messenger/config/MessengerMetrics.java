package com.premisave.messenger.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Custom Prometheus metrics for the messenger service.
 * Tracks message operations, delivery status, and real-time connections.
 */
@Slf4j
@Component
public class MessengerMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicInteger activeWebSocketConnections;

    // Message counters
    private final Counter messagesCreated;
    private final Counter messagesDeleted;
    private final Counter chatsCreated;
    private final Counter messageDeliveryFailed;
    private final Counter messageDeliveryPartial;

    // Message send latency timer
    private final Timer messageSendLatency;

    public MessengerMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.activeWebSocketConnections = new AtomicInteger(0);

        // Initialize counters
        this.messagesCreated = Counter.builder("messenger.messages.created.total")
                .description("Total number of messages created")
                .register(meterRegistry);

        this.messagesDeleted = Counter.builder("messenger.messages.deleted.total")
                .description("Total number of messages deleted")
                .register(meterRegistry);

        this.chatsCreated = Counter.builder("messenger.chats.created.total")
                .description("Total number of chats created")
                .register(meterRegistry);

        this.messageDeliveryFailed = Counter.builder("messenger.message.delivery.failed.total")
                .description("Total number of failed message deliveries")
                .register(meterRegistry);

        this.messageDeliveryPartial = Counter.builder("messenger.message.delivery.partial.total")
                .description("Total number of partially delivered messages")
                .register(meterRegistry);

        // Initialize timer for message send latency
        this.messageSendLatency = Timer.builder("messenger.message.send.latency")
                .description("Latency of sending messages to users")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        // Register gauge for active WebSocket connections
        Gauge.builder("messenger.websocket.connections.active", activeWebSocketConnections, AtomicInteger::get)
                .description("Number of active WebSocket connections")
                .register(meterRegistry);

        log.info("MessengerMetrics initialized successfully");
    }

    // Counter methods
    public void incrementMessagesCreated() {
        messagesCreated.increment();
    }

    public void incrementMessagesDeleted() {
        messagesDeleted.increment();
    }

    public void incrementChatsCreated() {
        chatsCreated.increment();
    }

    public void incrementMessageDeliveryFailed() {
        messageDeliveryFailed.increment();
    }

    public void incrementMessageDeliveryPartial() {
        messageDeliveryPartial.increment();
    }

    // Timer methods
    public Timer.Sample startMessageSendTimer() {
        return Timer.start(meterRegistry);
    }

    public void recordMessageSendLatency(Timer.Sample sample) {
        sample.stop(messageSendLatency);
    }

    // WebSocket connection tracking
    public void incrementWebSocketConnections() {
        activeWebSocketConnections.incrementAndGet();
    }

    public void decrementWebSocketConnections() {
        activeWebSocketConnections.decrementAndGet();
    }

    public int getActiveWebSocketConnections() {
        return activeWebSocketConnections.get();
    }
}