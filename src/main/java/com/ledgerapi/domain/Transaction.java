package com.ledgerapi.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An immutable, balanced record of money movement: two or more entries whose debits
 * and credits add up to the same total. Never updated or deleted once recorded.
 */
@Entity
@Table(name = "ledger_transaction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    /** The transaction this one reverses, if it is a reversal. */
    @Column(name = "reversal_of_id", length = 36)
    private String reversalOfId;

    /** Correlation id of the request/message that caused this transaction, for tracing. */
    @Column(name = "request_id", length = 64)
    private String requestId;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    @OrderColumn(name = "entry_order")
    private List<Entry> entries = new ArrayList<>();

    public Transaction(String description, String reversalOfId, String requestId) {
        this.id = UUID.randomUUID().toString();
        this.description = description;
        this.recordedAt = Instant.now();
        this.reversalOfId = reversalOfId;
        this.requestId = requestId;
    }

    public void addEntry(Entry entry) {
        entry.assignTo(this);
        entries.add(entry);
    }

    public boolean isReversal() {
        return reversalOfId != null;
    }
}

