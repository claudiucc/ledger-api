package com.ledgerapi.idempotency;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Tracks one client-supplied Idempotency-Key for one endpoint, so a retried request
 * gets back exactly the response the first attempt produced (or a clear conflict if the
 * same key is reused for a genuinely different request).
 */
@Entity
@Table(name = "idempotency_record")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

    public enum Status { IN_PROGRESS, COMPLETED }

    @Id
    @Column(name = "id", length = 160)
    private String id;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Setter
    private Status status;

    @Setter
    @Column(name = "response_status")
    private Integer responseStatus;

    @Setter
    @Column(name = "response_body", columnDefinition = "LONGTEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Setter
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public IdempotencyRecord(String id, String requestHash) {
        this.id = id;
        this.requestHash = requestHash;
        this.status = Status.IN_PROGRESS;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void complete(int responseStatus, String responseBody) {
        this.status = Status.COMPLETED;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.updatedAt = Instant.now();
    }
}

