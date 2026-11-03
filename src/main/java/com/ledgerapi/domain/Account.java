package com.ledgerapi.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Money held under one identifier, in one currency. Balances are kept as exact decimals
 * (MySQL DECIMAL, mapped to BigDecimal) - never floating point - so arithmetic never rounds.
 */
@Entity
@Table(name = "account")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    /** Null means no overdraft limit (unlimited), used for the ledger's own settlement accounts. */
    @Column(name = "overdraft_limit", precision = 19, scale = 4)
    private BigDecimal overdraftLimit;

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Account(String id, CurrencyCode currency, BigDecimal overdraftLimit) {
        this.id = id;
        this.currency = currency;
        this.overdraftLimit = overdraftLimit;
        this.balance = BigDecimal.ZERO.setScale(currency.decimalPlaces());
        this.createdAt = Instant.now();
    }

    /** @return the balance that would result after applying this signed delta, without persisting it. */
    public BigDecimal projectedBalance(BigDecimal signedDelta) {
        return balance.add(signedDelta);
    }

    public boolean wouldExceedOverdraft(BigDecimal projectedBalance) {
        return overdraftLimit != null && projectedBalance.negate().compareTo(overdraftLimit) > 0;
    }

    public void applyDelta(BigDecimal signedDelta) {
        this.balance = this.balance.add(signedDelta);
    }

    public boolean hasUnlimitedOverdraft() {
        return overdraftLimit == null;
    }
}

