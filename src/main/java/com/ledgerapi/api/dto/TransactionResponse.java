package com.ledgerapi.api.dto;

import com.ledgerapi.domain.Entry;
import com.ledgerapi.domain.Transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Response body for a recorded transaction, with all of its entries. */
public record TransactionResponse(
        String id,
        String description,
        Instant recordedAt,
        String reversalOfId,
        List<EntryResponse> entries) {

    public record EntryResponse(String accountId, String direction, BigDecimal amount, BigDecimal balanceAfter) {

        public static EntryResponse from(Entry entry) {
            return new EntryResponse(entry.getAccountId(), entry.getDirection().name(), entry.getAmount(), entry.getBalanceAfter());
        }
    }

    public static TransactionResponse from(Transaction transaction) {
        List<EntryResponse> entries = transaction.getEntries().stream().map(EntryResponse::from).toList();
        return new TransactionResponse(
                transaction.getId(),
                transaction.getDescription(),
                transaction.getRecordedAt(),
                transaction.getReversalOfId(),
                entries);
    }
}

