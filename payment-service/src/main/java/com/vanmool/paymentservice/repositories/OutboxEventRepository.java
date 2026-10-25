package com.vanmool.paymentservice.repositories;

import com.vanmool.paymentservice.models.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    // The next events to send, oldest first, at most 100 per round
    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();

    // One UPDATE by id. Saving the loaded entity instead would merge it, which first SELECTs the row and, if the row
    // has been deleted in the meantime, INSERTs it again
    @Transactional
    @Modifying
    @Query("UPDATE OutboxEvent e SET e.publishedAt = :publishedAt WHERE e.id = :id")
    int markPublished(@Param("id") UUID id, @Param("publishedAt") Instant publishedAt);

    // Housekeeping: sent events are kept for a while (to look at), then removed
    @Transactional
    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}
