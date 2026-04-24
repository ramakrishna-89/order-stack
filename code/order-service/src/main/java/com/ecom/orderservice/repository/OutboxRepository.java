package com.ecom.orderservice.repository;

import com.ecom.orderservice.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    // FOR UPDATE SKIP LOCKED: each instance locks its own batch; concurrent instances skip
    // already-locked rows instead of racing — prevents duplicate Kafka publishes in multi-pod deployments.
    // Requires the caller to run inside a @Transactional boundary (OutboxPoller.poll() provides this).
    @Query(value = "SELECT * FROM outbox_events WHERE published = false ORDER BY created_at LIMIT :batchSize FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEvent> findUnpublished(@Param("batchSize") int batchSize);

    // Single UPDATE instead of a load-then-save cycle — avoids fetching the full entity
    // just to flip one boolean. OutboxPoller uses this so it never needs the entity reference.
    @Modifying
    @Query("UPDATE OutboxEvent e SET e.published = true WHERE e.id = :id")
    void markAsPublished(@Param("id") UUID id);
}
