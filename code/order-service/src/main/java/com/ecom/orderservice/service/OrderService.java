package com.ecom.orderservice.service;

import com.ecom.orderservice.dto.CreateOrderRequest;
import com.ecom.orderservice.dto.OrderResponse;
import com.ecom.orderservice.dto.OrderResult;

import java.util.UUID;

public interface OrderService {

    OrderResult createOrder(CreateOrderRequest request, String idempotencyKey);

    OrderResponse getOrder(UUID id);
}
