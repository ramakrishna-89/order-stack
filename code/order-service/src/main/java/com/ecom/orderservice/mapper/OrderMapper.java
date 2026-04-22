package com.ecom.orderservice.mapper;

import com.ecom.orderservice.dto.CreateOrderRequest;
import com.ecom.orderservice.dto.OrderResponse;
import com.ecom.orderservice.model.Order;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct compile-time mapper — zero reflection, zero runtime overhead.
 *
 * MapStruct reads @Mapping annotations at compile time and generates a plain Java
 * implementation class (OrderMapperImpl) with direct field assignments — faster than
 * ModelMapper (reflection) and safer than hand-written from() factory methods.
 *
 * componentModel = "spring" → MapStruct generates @Component so Spring injects it normally.
 *
 * Processor order in pom.xml: Lombok runs first (generates getters/setters on Order),
 * then MapStruct reads those generated methods. Without that order, MapStruct sees an
 * empty class and generates a mapper that copies nothing.
 */
@Mapper(componentModel = "spring")
public interface OrderMapper {

    /**
     * CreateOrderRequest (record) → Order entity.
     *
     * JDK 16 — Record accessors have no "get" prefix: customerId(), amount(), etc.
     * MapStruct 1.5+ recognises record accessor methods as source properties automatically.
     *
     * Fields not present in the request (id, idempotencyKey, status, timestamps) are
     * explicitly ignored — OrderService sets them after mapping.
     */
    @Mapping(target = "id",             ignore = true)
    @Mapping(target = "idempotencyKey", ignore = true)
    @Mapping(target = "status",         ignore = true)
    @Mapping(target = "createdAt",      ignore = true)
    @Mapping(target = "updatedAt",      ignore = true)
    Order toEntity(CreateOrderRequest request);

    /**
     * Order entity → OrderResponse record.
     *
     * MapStruct maps Order's Lombok-generated getters (getId(), getCustomerId(), …) to the
     * OrderResponse record's canonical constructor parameters by matching names.
     * No @Mapping annotations needed — all field names align.
     */
    OrderResponse toResponse(Order order);
}
