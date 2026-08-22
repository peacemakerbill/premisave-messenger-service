package com.premisave.messenger.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
public class MessengerMetrics {

    private final Counter messagesCreated;
    private final Counter messagesDeleted;
    private final Counter chatsCreated;
    private final Timer messageLatency;
    private final AtomicInteger activeWebSocketConnections;
    private final Counter failedDeliveries;
    private final Counter partialDeliveries;

    public MessengerMetrics(MeterRegistry meterRegistry) {
        this.messagesCreated = Counter.builder("messenger.messages.created.total")
            .description("Total messages created")
            .register(meterRegistry);

        this.messagesDeleted = Counter.builder("messenger.messages.deleted.total")
            .description("Total messages deleted")
            .register(meterRegistry);

        this.chatsCreated = Counter.builder("messenger.chats.created.total")
            .description("Total chats created")
            .register(meterRegistry);

        this.messageLatency = Timer.builder("messenger.message.send.latency")
            .description("Message send latency in milliseconds")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);

        this.activeWebSocketConnections = meterRegistry.gauge(
            "messenger.websocket.connections.active",
            new AtomicInteger(0)
        );

        this.failedDeliveries = Counter.builder("messenger.message.delivery.failed.total")
            .description("Total failed message deliveries")
            .register(meterRegistry);

        this.partialDeliveries = Counter.builder("messenger.message.delivery.partial.total")
            .description("Total partial message deliveries")
            .register(meterRegistry);
    }

    public void recordMessageCreated() {
        messagesCreated.increment();
    }

    public void recordMessageDeleted() {
        messagesDeleted.increment();
    }

    public void recordChatCreated() {
        chatsCreated.increment();
    }

    public void recordMessageLatency(long durationMs) {
        messageLatency.record(Duration.ofMillis(durationMs));
    }

    public void recordMessageLatency(Duration duration) {
        messageLatency.record(duration);
    }

    public void incrementWebSocketConnections() {
        activeWebSocketConnections.incrementAndGet();
    }

    public void decrementWebSocketConnections() {
        activeWebSocketConnections.decrementAndGet();
    }

    public int getActiveWebSocketConnections() {
        return activeWebSocketConnections.get();
    }

    public void recordFailedDelivery() {
        failedDeliveries.increment();
    }

    public void recordPartialDelivery() {
        partialDeliveries.increment();
    }
}