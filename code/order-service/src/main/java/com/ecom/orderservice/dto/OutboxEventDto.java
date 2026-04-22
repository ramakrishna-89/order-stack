package com.ecom.orderservice.dto;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

// DTO for the outbox_events table row — used by OutboxPoller so the infrastructure layer
// never imports the model entity directly. Keeps the model confined to the persistence layer.
@Value
@Builder
public class OutboxEventDto {
    UUID   id;
    String aggregateId;
    String eventType;
    String payload;
    String traceId;
    String spanId;
}
