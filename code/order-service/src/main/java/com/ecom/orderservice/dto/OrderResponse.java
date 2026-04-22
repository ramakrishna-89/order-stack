package com.ecom.orderservice.dto;

import com.ecom.orderservice.model.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// MapStruct uses the Lombok-generated builder to construct this DTO from an Order entity.
// @NoArgsConstructor + @AllArgsConstructor ensure Jackson can deserialise it (e.g. from Redis cache).
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {

    private UUID id;
    private String customerId;
    private OrderStatus status;
    private BigDecimal amount;
    private String currency;
    private String description;
    private Instant createdAt;
    private Instant updatedAt;
}
