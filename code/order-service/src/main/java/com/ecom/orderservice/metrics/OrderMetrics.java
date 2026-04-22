package com.ecom.orderservice.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderMetrics {

    private final MeterRegistry registry;

    public void incrementOrdersCreated() {
        Counter.builder("orders_created_total")
                .description("Total number of orders successfully created")
                .register(registry)
                .increment();
    }

    public void incrementDuplicateRejected() {
        Counter.builder("duplicate_orders_rejected_total")
                .description("Total number of duplicate orders rejected via idempotency key")
                .register(registry)
                .increment();
    }

    public void incrementEventPublished() {
        Counter.builder("outbox_events_published_total")
                .description("Total number of outbox events published to Kafka")
                .register(registry)
                .increment();
    }

    public void incrementEventPublishFailed() {
        Counter.builder("outbox_events_publish_failed_total")
                .description("Total number of outbox events that failed to publish")
                .register(registry)
                .increment();
    }
}
