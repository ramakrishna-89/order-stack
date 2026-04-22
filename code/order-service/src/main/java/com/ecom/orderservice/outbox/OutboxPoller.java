package com.ecom.orderservice.outbox;

import com.ecom.orderservice.dto.OutboxEventDto;
import com.ecom.orderservice.event.OrderEvent;
import com.ecom.orderservice.mapper.OutboxMapper;
import com.ecom.orderservice.repository.OutboxRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private final OutboxRepository       outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxMapper           outboxMapper;   // entity → DTO; model stays in persistence layer
    private final ApplicationEventPublisher eventPublisher;

    @Value("${kafka.topic.order-events}")
    private String orderEventsTopic;

    @Value("${outbox.poller.batch-size:100}")
    private int batchSize;

    // fixedDelay: next poll starts only after current one completes — prevents concurrent execution
    @Scheduled(fixedDelayString = "${outbox.poller.fixed-delay-ms:1000}")
    @Transactional
    public void poll() {
        // JDK 10 — var; repository returns entities, mapper converts to DTOs immediately.
        // From this point on, OutboxPoller works only with OutboxEventDto — no model imports.
        // JDK 16 — Stream.toList(): unmodifiable, no Collectors.toList() boilerplate
        var dtos = outboxRepository.findUnpublished(batchSize)
                .stream()
                .map(outboxMapper::toDto) // MapStruct method reference
                .toList();               // JDK 16

        if (dtos.isEmpty()) return;

        log.debug("OUTBOX_POLL found={} events", dtos.size());

        dtos.forEach(dto -> {
            try {
                publishWithContext(dto);
                // Single UPDATE query — no entity load needed, markAsPublished takes only the ID
                outboxRepository.markAsPublished(dto.getId());
                eventPublisher.publishEvent(
                        new OrderEvent.EventPublished(dto.getAggregateId(), dto.getEventType(), Instant.now()));
            } catch (Exception e) {
                // Leave published=false — outbox guarantees at-least-once delivery on next poll
                eventPublisher.publishEvent(
                        new OrderEvent.EventPublishFailed(dto.getAggregateId(), e.getMessage(), Instant.now()));
            }
        });

        // Stream to collect published IDs for a single summary log line
        List<String> published = dtos.stream()
                .map(OutboxEventDto::getAggregateId)
                .toList();

        log.info("OUTBOX_BATCH_DONE attempted={} orderIds={}", published.size(), published);
    }

    // Restores the original HTTP request's trace context as OTel parent before publishing.
    // KafkaTemplate.send() runs inside that context and injects traceparent into Kafka headers,
    // so notification-service's @KafkaListener continuation spans under the same trace.
    private void publishWithContext(OutboxEventDto dto) {
        if (dto.getTraceId() != null && dto.getSpanId() != null) {
            SpanContext parent = SpanContext.createFromRemoteParent(
                    dto.getTraceId(), dto.getSpanId(),
                    TraceFlags.getSampled(), TraceState.getDefault());
            try (Scope ignored = Context.root().with(Span.wrap(parent)).makeCurrent()) {
                publish(dto);
            }
        } else {
            publish(dto);
        }
    }

    @CircuitBreaker(name = "kafka-publisher", fallbackMethod = "publishFallback")
    private void publish(OutboxEventDto dto) {
        // Partition by aggregateId (orderId) — guarantees ordering per order
        kafkaTemplate.send(orderEventsTopic, dto.getAggregateId(), dto.getPayload())
                .whenComplete((result, ex) -> {
                    if (ex != null) throw new RuntimeException("Kafka send failed", ex);
                });
    }

    private void publishFallback(OutboxEventDto dto, Throwable t) {
        log.warn("CIRCUIT_BREAKER_OPEN kafka unavailable, orderId={} will retry on next poll",
                dto.getAggregateId());
        throw new RuntimeException("Circuit breaker open — Kafka unavailable", t);
    }
}
