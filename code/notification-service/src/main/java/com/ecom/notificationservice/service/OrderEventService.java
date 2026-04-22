package com.ecom.notificationservice.service;

import com.ecom.notificationservice.dto.NotificationPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Slf4j
@Service
public class OrderEventService {

    /**
     * Sinks.Many — the reactive bridge between imperative Kafka (@KafkaListener)
     * and the reactive SSE pipeline (Flux).
     *
     * directBestEffort():
     *   - Thread-safe: multiple Kafka listener threads can call tryEmitNext() concurrently
     *   - If no SSE clients connected → returns FAIL_ZERO_SUBSCRIBER (no buffering, no memory leak)
     *   - If a slow subscriber can't keep up → drops for that subscriber, others unaffected
     */
    private final Sinks.Many<NotificationPayload> sink =
            Sinks.many().multicast().directBestEffort();

    /**
     * Called by OrderEventConsumer to push a Kafka event into the reactive pipeline.
     * tryEmitNext() is non-blocking and thread-safe.
     */
    public void emit(NotificationPayload payload) {
        var result = sink.tryEmitNext(payload);

        // JDK 14 — Switch expression on the EmitResult enum
        switch (result) {
            case OK                    -> log.debug("EVENT_EMITTED orderId={}", payload.orderId());
            case FAIL_ZERO_SUBSCRIBER  -> log.debug("NO_SSE_CLIENTS orderId={} dropped", payload.orderId());
            default                    -> log.warn("EMIT_FAILED orderId={} result={}", payload.orderId(), result);
        }
    }

    /**
     * Exposes the event stream as a hot Flux.
     *
     * .asFlux() converts the Sink → Flux (read-only view).
     * Hot publisher: events flow regardless of subscribers — new subscribers see
     * only events emitted AFTER they subscribe (correct for a live dashboard).
     *
     * Each SSE connection in the controller subscribes to this Flux independently.
     * Disconnecting clients unsubscribe automatically — no registry needed.
     */
    public Flux<NotificationPayload> orderEvents() {
        return sink.asFlux()
                .onBackpressureDrop(dropped ->
                        log.warn("SSE_BACKPRESSURE_DROP orderId={} slow consumer", dropped.orderId()));
    }
}
