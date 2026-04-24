package com.ecom.orderservice.service;

import com.ecom.orderservice.model.Order;
import com.ecom.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class OrderSaveDelegate {

    private final OrderRepository orderRepository;

    // REQUIRES_NEW: runs in its own transaction so a constraint violation rolls back only this
    // inner tx — the caller's transaction remains valid for the fallback idempotency lookup.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Order save(Order order) {
        return orderRepository.save(order);
    }
}
