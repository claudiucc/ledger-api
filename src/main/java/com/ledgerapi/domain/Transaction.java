package com.ledgerapi.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An immutable, balanced record of money movement: two or more entries whose debits
 * and credits add up to the same total. Never updated or deleted once recorded.
 *
 * <p>Implements {@link Persistable}: the id is assigned by the application (a fresh UUID), not
 * the database, and there is no {@code @Version} field for Spring Data to infer newness from.
 * Without it, saving a brand-new transaction would go through {@code EntityManager.merge()}
 * instead of {@code persist()} - and since the {@code entries} association only cascades
 * {@code PERSIST} (deliberately: entries are never updated, so there is nothing to cascade a
 * merge to), a merged transaction's entries would fail to save at all.
 */
@Entity
@Table(name = "ledger_transaction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction implements Persistable<String> {

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
    @OrderBy("entryOrder asc")
    private List<Entry> entries = new ArrayList<>();

    @Transient
    private boolean isNew = true;

    public Transaction(String description, String reversalOfId, String requestId) {
        this.id = UUID.randomUUID().toString();
        this.description = description;
        this.recordedAt = Instant.now();
        this.reversalOfId = reversalOfId;
        this.requestId = requestId;
    }

    public void addEntry(Entry entry) {
        entry.assignTo(this, entries.size());
        entries.add(entry);
    }

    public boolean isReversal() {
        return reversalOfId != null;
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

