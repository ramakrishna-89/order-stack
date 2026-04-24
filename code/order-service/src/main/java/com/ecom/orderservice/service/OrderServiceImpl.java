package com.ecom.orderservice.service;

import com.ecom.orderservice.dto.CreateOrderRequest;
import com.ecom.orderservice.dto.OrderResponse;
import com.ecom.orderservice.dto.OrderResult;
import com.ecom.orderservice.event.OrderEvent;
import com.ecom.orderservice.mapper.OrderMapper;
import com.ecom.orderservice.model.OrderStatus;
import com.ecom.orderservice.model.OutboxEvent;
import com.ecom.orderservice.repository.OrderRepository;
import com.ecom.orderservice.repository.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository           orderRepository;
    private final OutboxRepository          outboxRepository;
    private final StringRedisTemplate       redisTemplate;
    private final ObjectMapper              objectMapper;
    private final OrderMapper               orderMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final OrderSaveDelegate         orderSaveDelegate;

    @Value("${order.cache.ttl-seconds:30}")
    private long cacheTtlSeconds;

    // ── POST /orders ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    public OrderResult createOrder(CreateOrderRequest request, String idempotencyKey) {

        // JDK 10 — var: local-variable type inference
        var idemCacheKey = "idem:" + idempotencyKey;
        var cached       = redisTemplate.opsForValue().get(idemCacheKey);

        if (cached != null) {
            // Fire-and-forget: metric + log dispatched to a virtual thread; request thread unblocked
            eventPublisher.publishEvent(
                    new OrderEvent.DuplicateRejected(idempotencyKey, request.getCustomerId(), Instant.now()));

            return OrderResult.builder()
                    .order(deserialize(cached, OrderResponse.class))
                    .duplicate(true)
                    .build();
        }

        // Redis miss — check DB before inserting; handles the common case where the key exists in DB
        // but was evicted from Redis (TTL expired or memory pressure). Avoids exception-driven flow
        // for this frequent path — the REQUIRES_NEW catch block handles only the true concurrent race.
        var dbExisting = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (dbExisting.isPresent()) {
            eventPublisher.publishEvent(
                    new OrderEvent.DuplicateRejected(idempotencyKey, request.getCustomerId(), Instant.now()));
            return OrderResult.builder().order(orderMapper.toResponse(dbExisting.get())).duplicate(true).build();
        }

        log.info("ORDER_RECEIVED customerId={} amount={} idempotencyKey={}",
                request.getCustomerId(), request.getAmount(), idempotencyKey);

        // MapStruct: Lombok getters on CreateOrderRequest → entity setters on Order
        var order = orderMapper.toEntity(request);
        order.setIdempotencyKey(idempotencyKey);
        order.setStatus(OrderStatus.CREATED);
        try {
            order = orderSaveDelegate.save(order);
        } catch (DataIntegrityViolationException e) {
            // REQUIRES_NEW rolled back; outer tx still valid — look up the winner and return it
            // so the client gets HTTP 200 + existing order instead of a 409 requiring a retry.
            var existing = orderRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Race recovery failed"));
            eventPublisher.publishEvent(
                    new OrderEvent.DuplicateRejected(idempotencyKey, request.getCustomerId(), Instant.now()));
            return OrderResult.builder()
                    .order(orderMapper.toResponse(existing))
                    .duplicate(true)
                    .build();
        }

        // Serialize once — reused for outbox payload AND both Redis cache entries
        var response   = orderMapper.toResponse(order);
        var serialized = serialize(response);

        // Capture current OTel span context so the OutboxPoller can restore it as parent
        // when publishing to Kafka — giving a single trace from HTTP → Kafka → SSE.
        var spanCtx = Span.current().getSpanContext();
        var traceId = spanCtx.isValid() ? spanCtx.getTraceId() : null;
        var spanId  = spanCtx.isValid() ? spanCtx.getSpanId()  : null;

        // OutboxEvent entity: stays in the service layer — never crosses to controller or poller
        outboxRepository.save(OutboxEvent.builder()
                .aggregateId(order.getId().toString())
                .eventType("ORDER_CREATED")
                .payload(serialized)
                .traceId(traceId)
                .spanId(spanId)
                .published(false)
                .build());

        eventPublisher.publishEvent(
                new OrderEvent.OrderCreated(order.getId(), order.getCustomerId(), idempotencyKey, serialized, Instant.now()));

        return OrderResult.builder()
                .order(response)
                .duplicate(false)
                .build();
    }

    // ── GET /orders/{id} ─────────────────────────────────────────────────────

    @Override
    public OrderResponse getOrder(UUID id) {
        var cacheKey = "order:" + id;

        // JDK 8+ — Optional pipeline: cache-hit → deserialise, cache-miss → DB + re-cache
        return Optional.ofNullable(redisTemplate.opsForValue().get(cacheKey))
                .map(json -> {
                    log.debug("ORDER_CACHE_HIT orderId={}", id);
                    return deserialize(json, OrderResponse.class);
                })
                .orElseGet(() -> fetchFromDbAndCache(id, cacheKey));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private OrderResponse fetchFromDbAndCache(UUID id, String cacheKey) {
        var order = orderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found: " + id));
        var response = orderMapper.toResponse(order);
        redisTemplate.opsForValue().set(cacheKey, serialize(response), java.time.Duration.ofSeconds(cacheTtlSeconds));
        log.debug("ORDER_CACHE_MISS orderId={}", id);
        return response;
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("Serialization failed", e);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new RuntimeException("Deserialization failed", e);
        }
    }
}
