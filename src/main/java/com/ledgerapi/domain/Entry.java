package com.ledgerapi.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One posting within a transaction: an account, a direction and a positive amount,
 * plus the balance that account had immediately after this entry was applied.
 */
@Entity
@Table(name = "ledger_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Entry {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 6)
    private Direction direction;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    /**
     * Position within the transaction's entry list, assigned explicitly by
     * {@link Transaction#addEntry} rather than left to JPA's {@code @OrderColumn} to manage:
     * that annotation needs the owning side's collection to be a Hibernate-tracked
     * {@code PersistentList} to populate this column on cascade-persist, which a plain
     * {@code mappedBy} collection on a brand-new parent is not.
     */
    @Column(name = "entry_order", nullable = false)
    private int entryOrder;

    public Entry(String accountId, Direction direction, BigDecimal amount, BigDecimal balanceAfter) {
        this.id = UUID.randomUUID().toString();
        this.accountId = accountId;
        this.direction = direction;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
    }

    void assignTo(Transaction transaction, int entryOrder) {
        this.transaction = transaction;
        this.entryOrder = entryOrder;
    }

    /** The signed effect of this entry on the account's balance: +amount for credit, -amount for debit. */
    public BigDecimal signedDelta() {
        return direction == Direction.CREDIT ? amount : amount.negate();
    }

    public Entry flipped(BigDecimal newBalanceAfter) {
        Direction flippedDirection = direction == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT;
        return new Entry(accountId, flippedDirection, amount, newBalanceAfter);
    }
}

