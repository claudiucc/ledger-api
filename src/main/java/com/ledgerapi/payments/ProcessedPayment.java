package com.ledgerapi.payments;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Records that a given payment id has already been turned into a ledger transaction,
 * so redelivery - even under a brand-new event id - never records it twice.
 */
@Entity
@Table(name = "processed_payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedPayment {

    @Id
    @Column(name = "payment_id", length = 128)
    private String paymentId;

    @Column(name = "event_id", nullable = false, length = 128)
    private String eventId;

    @Column(name = "transaction_id", nullable = false, length = 36)
    private String transactionId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    public ProcessedPayment(String paymentId, String eventId, String transactionId) {
        this.paymentId = paymentId;
        this.eventId = eventId;
        this.transactionId = transactionId;
        this.processedAt = Instant.now();
    }
}

