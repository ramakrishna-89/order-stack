package com.ecom.orderservice.mapper;

import com.ecom.orderservice.dto.OutboxEventDto;
import com.ecom.orderservice.model.OutboxEvent;
import org.mapstruct.Mapper;

// Compile-time mapper: OutboxEvent entity → OutboxEventDto.
// All field names match (id, aggregateId, eventType, payload) — no @Mapping annotations needed.
@Mapper(componentModel = "spring")
public interface OutboxMapper {
    OutboxEventDto toDto(OutboxEvent event);
}
