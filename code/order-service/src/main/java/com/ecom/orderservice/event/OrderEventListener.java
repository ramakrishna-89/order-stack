package com.ecom.orderservice.event;

import com.ecom.orderservice.metrics.OrderMetrics;
import com.ecom.orderservice.model.OrderStatus;
import com.ecom.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private final OrderMetrics          metrics;
    private final OrderRepository       orderRepository;
    private final StringRedisTemplate   redisTemplate;

    @Value("${order.cache.ttl-seconds:30}")
    private long cacheTtlSeconds;

    @Value("${order.idempotency.ttl-hours:24}")
    private long idempotencyTtlHours;

    /**
     * JDK 21 — Virtual threads + Spring @Async: fire-and-forget side effects.
     *
     * @Async("virtualThreadExecutor") moves this handler off the request thread onto a
     * virtual thread. The publisher (OrderService) returns immediately — it does NOT wait
     * for metrics or logging to complete. Under 5,000 orders/min this eliminates ~2–5ms
     * of blocking I/O per request that would otherwise add to p99 latency.
     *
     * JDK 21 — Pattern matching in switch (JEP 441, finalized in JDK 21).
     * Because OrderEvent is a sealed interface with a known set of subtypes, the compiler
     * verifies the switch is exhaustive — no `default` branch required or allowed.
     */
    @EventListener
    @Async("virtualThreadExecutor")
    public void handle(OrderEvent event) {
        // JDK 21 — Exhaustive pattern-matching switch over sealed type
        switch (event) {
            case OrderEvent.OrderCreated e -> {
                metrics.incrementOrdersCreated();
                log.info("ORDER_CREATED orderId={} customerId={}", e.orderId(), e.customerId());
            }
            case OrderEvent.DuplicateRejected e -> {
                metrics.incrementDuplicateRejected();
                log.info("DUPLICATE_REJECTED idempotencyKey={} customerId={}", e.idempotencyKey(), e.customerId());
            }
            case OrderEvent.EventPublished e -> {
                metrics.incrementEventPublished();
                log.info("EVENT_PUBLISHED aggregateId={} eventType={}", e.aggregateId(), e.eventType());
                if ("ORDER_CREATED".equals(e.eventType())) {
                    advanceAndInvalidate(e.aggregateId(), OrderStatus.PUBLISHED);
                }
            }
            case OrderEvent.EventPublishFailed e -> {
                metrics.incrementEventPublishFailed();
                log.warn("EVENT_PUBLISH_FAILED aggregateId={} error={}", e.aggregateId(), e.error());
                advanceAndInvalidate(e.aggregateId(), OrderStatus.FAILED);
            }
        }
    }

    // Fires only after the JPA transaction commits — Redis never sees a rolled-back order.
    // @Async ensures the Kafka/Redis I/O doesn't block the commit thread.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("virtualThreadExecutor")
    public void cacheAfterCommit(OrderEvent.OrderCreated e) {
        redisTemplate.executePipelined((RedisCallback<Object>) conn -> {
            var sc = (StringRedisConnection) conn;
            sc.setEx("idem:"  + e.idempotencyKey(), idempotencyTtlHours * 3600, e.serialized());
            sc.setEx("order:" + e.orderId(),        cacheTtlSeconds,             e.serialized());
            return null;
        });
        log.debug("ORDER_CACHED orderId={}", e.orderId());
    }

    private void advanceAndInvalidate(String aggregateId, OrderStatus status) {
        var id = UUID.fromString(aggregateId);
        orderRepository.advanceStatus(id, status);
        redisTemplate.delete("order:" + id);
    }
}
