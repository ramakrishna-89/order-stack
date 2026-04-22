package com.ecom.orderservice.repository;

import com.ecom.orderservice.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query("SELECT e FROM OutboxEvent e WHERE e.published = false ORDER BY e.createdAt ASC LIMIT :batchSize")
    List<OutboxEvent> findUnpublished(@Param("batchSize") int batchSize);

    // Single UPDATE instead of a load-then-save cycle — avoids fetching the full entity
    // just to flip one boolean. OutboxPoller uses this so it never needs the entity reference.
    @Modifying
    @Query("UPDATE OutboxEvent e SET e.published = true WHERE e.id = :id")
    void markAsPublished(@Param("id") UUID id);
}
