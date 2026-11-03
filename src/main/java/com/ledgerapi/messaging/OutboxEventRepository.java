package com.ledgerapi.messaging;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import jakarta.persistence.QueryHint;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    /**
     * SKIP LOCKED lets several ledger instances poll the same table concurrently without
     * fighting over the same rows or blocking each other - each picks up a different batch.
     */
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query(value = "select * from outbox_event where published_at is null order by created_at asc limit :limit for update skip locked",
            nativeQuery = true)
    List<OutboxEvent> lockNextBatch(@org.springframework.data.repository.query.Param("limit") int limit);
}

