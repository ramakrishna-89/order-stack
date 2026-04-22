package com.ecom.notificationservice.controller;

import com.ecom.notificationservice.dto.NotificationPayload;
import com.ecom.notificationservice.metrics.NotificationMetrics;
import com.ecom.notificationservice.service.OrderEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;

@Slf4j
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final OrderEventService  orderEventService;
    private final NotificationMetrics metrics;

    @Value("${notification.sse.heartbeat-interval-seconds:30}")
    private int heartbeatIntervalSeconds;

    /**
     * SSE endpoint — returns a Flux that IS the open HTTP connection.
     *
     * WebFlux maps Flux<ServerSentEvent<T>> directly to the SSE wire protocol:
     *   data: {...json...}\n\n      ← order event
     *   : keep-alive\n\n            ← heartbeat comment (invisible to JS onmessage)
     *
     * Spring manages the connection lifecycle:
     *   - Client connects    → Flux subscription starts
     *   - Client disconnects → Flux subscription cancelled automatically
     *   - No SseEmitter, no registry, no lifecycle callbacks needed
     *
     * Netty serves all connections on ~2x CPU event loop threads.
     * 10,000 concurrent SSE clients = 10,000 Flux subscriptions, not 10,000 threads.
     *
     * Angular usage:
     *   const es = new EventSource('http://localhost:8081/notifications/stream');
     *   es.addEventListener('PENDING', e => console.log(JSON.parse(e.data)));
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<NotificationPayload>> stream() {

        // Order events — each Kafka message becomes one SSE event
        Flux<ServerSentEvent<NotificationPayload>> events = orderEventService.orderEvents()
                .doOnSubscribe(s -> {
                    metrics.connectionOpened();
                    log.info("SSE_CONNECTED activeConnections={}", metrics.activeConnections());
                })
                .doOnCancel(() -> {
                    metrics.connectionClosed();
                    log.info("SSE_DISCONNECTED activeConnections={}", metrics.activeConnections());
                })
                .map(payload -> ServerSentEvent.<NotificationPayload>builder()
                        .id(payload.orderId().toString())   // Last-Event-ID for reconnect
                        .event(payload.status())            // Angular listens by event name
                        .data(payload)
                        .build());

        // Heartbeat — SSE comment every N seconds keeps LB/proxy from closing idle connections.
        // Flux.interval() runs on Reactor's parallel scheduler — no @Scheduled, no thread pool.
        // ServerSentEvent with only comment() → wire format: ": keep-alive\n\n"
        Flux<ServerSentEvent<NotificationPayload>> heartbeat = Flux
                .interval(Duration.ofSeconds(heartbeatIntervalSeconds))
                .map(tick -> ServerSentEvent.<NotificationPayload>builder()
                        .comment("keep-alive")
                        .build());

        // Initial comment flushed immediately on subscribe — forces WebFlux to send HTTP response
        // headers right away. Without this, headers are buffered until the first real event,
        // so EventSource.onopen never fires and the browser shows "Connecting..." indefinitely.
        Flux<ServerSentEvent<NotificationPayload>> connected = Flux.just(
                ServerSentEvent.<NotificationPayload>builder().comment("connected").build()
        );

        // Flux.concat(connected, merge): headers flush instantly, then events + heartbeats interleave.
        return Flux.concat(connected, Flux.merge(events, heartbeat));
    }
}
