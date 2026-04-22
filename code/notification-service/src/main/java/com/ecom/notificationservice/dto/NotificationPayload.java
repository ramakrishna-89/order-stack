package com.ecom.notificationservice.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JDK 16 — Record: the Notification Service defines its own view of the event.
 * Maps the OrderResponse JSON from Kafka — only fields the dashboard needs.
 *
 * Service boundary: this record is NOT shared with Order Service.
 * The Notification Service owns its own contract independently.
 *
 * @JsonAlias("id") accepts "id" from OrderResponse Kafka JSON → orderId during deserialization.
 * Serialization uses the field name "orderId" so SSE clients receive the correct key.
 */
public record NotificationPayload(

        @JsonAlias("id")
        UUID orderId,

        String status,
        BigDecimal amount,
        String currency,
        Instant createdAt

) {}
