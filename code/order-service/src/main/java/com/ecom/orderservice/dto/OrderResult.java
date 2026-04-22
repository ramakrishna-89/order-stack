package com.ecom.orderservice.dto;

import lombok.Builder;
import lombok.Value;

// @Value = immutable Lombok class: all fields private final, getters generated.
// For boolean field `duplicate`, Lombok generates isDuplicate() — matches Java bean convention.
@Value
@Builder
public class OrderResult {
    OrderResponse order;
    boolean duplicate;
}
