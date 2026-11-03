package com.ledgerapi.api.dto;

import com.ledgerapi.domain.Entry;

import java.math.BigDecimal;

/** One item in a paged account-entries listing: {@code GET /v1/accounts/{id}/entries}. */
public record EntryPageItem(String transactionId, String accountId, String direction, BigDecimal amount, BigDecimal balanceAfter) {

    public static EntryPageItem from(Entry entry) {
        return new EntryPageItem(
                entry.getTransaction() != null ? entry.getTransaction().getId() : null,
                entry.getAccountId(),
                entry.getDirection().name(),
                entry.getAmount(),
                entry.getBalanceAfter());
    }
}

