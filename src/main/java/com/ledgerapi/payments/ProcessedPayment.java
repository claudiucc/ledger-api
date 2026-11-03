package com.ledgerapi.payments;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * Records that a given payment id has already been turned into a ledger transaction,
 * so redelivery - even under a brand-new event id - never records it twice.
 *
 * <p>Implements {@link Persistable} for the same reason as {@code IdempotencyRecord}: the id is
 * assigned by the application, so without it {@code save()} would {@code merge()} (silently
 * updating an existing row) instead of {@code persist()} (inserting, and throwing a
 * unique-constraint violation when two concurrent deliveries of the same payment race each
 * other) - which is exactly the signal {@link com.ledgerapi.ledger.LedgerService#recordPaymentSettled}
 * relies on to detect the race.
 */
@Entity
@Table(name = "processed_payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedPayment implements Persistable<String> {

    @Id
    @Column(name = "payment_id", length = 128)
    private String paymentId;

    @Column(name = "event_id", nullable = false, length = 128)
    private String eventId;

    @Column(name = "transaction_id", nullable = false, length = 36)
    private String transactionId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Transient
    private boolean isNew = true;

    public ProcessedPayment(String paymentId, String eventId, String transactionId) {
        this.paymentId = paymentId;
        this.eventId = eventId;
        this.transactionId = transactionId;
        this.processedAt = Instant.now();
    }

    @Override
    public String getId() {
        return paymentId;
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

