package com.ecom.orderservice.event;

import java.time.Instant;
import java.util.UUID;

/**
 * JDK 17 — Sealed interface (JEP 409, finalized in JDK 17).
 *
 * A sealed interface declares exactly which types can implement it via the `permits` clause.
 * The compiler enforces exhaustiveness: a switch on an OrderEvent must handle every subtype,
 * or the code won't compile. No "default" escape hatch needed.
 *
 * Combined with records (JDK 16), this gives us a type-safe, exhaustive event hierarchy
 * with zero boilerplate — a pattern equivalent to algebraic data types (ADTs) in functional languages.
 */
public sealed interface OrderEvent
        permits OrderEvent.OrderCreated,
                OrderEvent.DuplicateRejected,
                OrderEvent.EventPublished,
                OrderEvent.EventPublishFailed {

    Instant occurredAt();

    // ── Subtypes ────────────────────────────────────────────────────────────────

    /**
     * JDK 16 — Records (JEP 395, finalized in JDK 16).
     *
     * Records are immutable data carriers: the compiler generates the canonical constructor,
     * accessors (no "get" prefix), equals(), hashCode(), and toString() automatically.
     * Records implementing a sealed interface must be listed in its permits clause.
     */
    record OrderCreated(UUID orderId, String customerId, String idempotencyKey, String serialized, Instant occurredAt)
            implements OrderEvent {}

    record DuplicateRejected(String idempotencyKey, String customerId, Instant occurredAt)
            implements OrderEvent {}

    record EventPublished(String aggregateId, String eventType, Instant occurredAt)
            implements OrderEvent {}

    record EventPublishFailed(String aggregateId, String error, Instant occurredAt)
            implements OrderEvent {}
}
