package com.ecom.notificationservice.consumer;

import com.ecom.notificationservice.dto.NotificationPayload;
import com.ecom.notificationservice.service.OrderEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final OrderEventService orderEventService;
    private final ObjectMapper      objectMapper;
    private final Tracer            tracer;

    /**
     * Imperative → Reactive bridge.
     *
     * @KafkaListener (Spring Kafka) handles:
     *   - Unique consumer group per pod (notification-${random.uuid})
     *   - Offset commits, partition assignment, rebalancing
     *   - Retry and error handling
     *
     * On each event: parse JSON → emit into Sinks.Many → reactive Flux fans out to SSE clients.
     * The Kafka thread is released immediately after tryEmitNext() — non-blocking.
     */
    @KafkaListener(
            topics      = "${kafka.topic.order-events}",
            groupId     = "${spring.kafka.consumer.group-id}",
            concurrency = "1"   // single consumer thread reads all partitions in order
    )
    public void consume(ConsumerRecord<String, String> record) {
        log.debug("KAFKA_RECEIVED orderId={} partition={} offset={}",
                record.key(), record.partition(), record.offset());

        // JDK 8+ — Optional pipeline: parse failure → log and skip, no exception propagation
        Optional.ofNullable(parse(record.value()))
                .ifPresentOrElse(
                        payload -> {
                            var span = tracer.nextSpan().name("sse.dispatch").start();
                            try (var ws = tracer.withSpan(span)) {
                                span.tag("orderId", payload.orderId().toString());
                                orderEventService.emit(payload);
                            } finally {
                                span.end();
                            }
                        },
                        () -> log.warn("PARSE_FAILED orderId={}", record.key())
                );
    }

    private NotificationPayload parse(String json) {
        try {
            return objectMapper.readValue(json, NotificationPayload.class);
        } catch (Exception e) {
            return null;
        }
    }
}
