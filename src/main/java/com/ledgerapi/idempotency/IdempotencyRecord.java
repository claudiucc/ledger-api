package com.ledgerapi.idempotency;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * Tracks one client-supplied Idempotency-Key for one endpoint, so a retried request
 * gets back exactly the response the first attempt produced (or a clear conflict if the
 * same key is reused for a genuinely different request).
 *
 * <p>Implements {@link Persistable} because the id is assigned by the application, not the
 * database: without it, Spring Data JPA treats every instance with a non-null id as "already
 * existing" and calls {@code EntityManager.merge()} on save, which silently
 * <em>updates</em> a row that already has that id instead of inserting and throwing a
 * unique-constraint violation - which is exactly the signal two racing requests claiming the
 * same key need in order to tell who won.
 */
@Entity
@Table(name = "idempotency_record")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord implements Persistable<String> {

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

    @Transient
    private boolean isNew = true;

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

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PrePersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }
}

