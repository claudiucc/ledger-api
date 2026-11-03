package com.ledgerapi.messaging;

import com.ledgerapi.domain.Entry;
import com.ledgerapi.domain.Transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The JSON shape published on the outbox/Kafka topic when a transaction is recorded.
 * Kept independent of the JPA entity so the wire format doesn't change just because the
 * persistence model does.
 */
public record TransactionRecordedPayload(
        String transactionId,
        String description,
        Instant recordedAt,
        String reversalOfId,
        String requestId,
        List<EntryPayload> entries) {

    public record EntryPayload(String accountId, String direction, BigDecimal amount, BigDecimal balanceAfter) {
    }

    public static TransactionRecordedPayload from(Transaction transaction) {
        List<EntryPayload> entries = transaction.getEntries().stream()
                .map(TransactionRecordedPayload::toEntryPayload)
                .toList();
        return new TransactionRecordedPayload(
                transaction.getId(),
                transaction.getDescription(),
                transaction.getRecordedAt(),
                transaction.getReversalOfId(),
                transaction.getRequestId(),
                entries);
    }

    private static EntryPayload toEntryPayload(Entry entry) {
        return new EntryPayload(
                entry.getAccountId(),
                entry.getDirection().name(),
                entry.getAmount(),
                entry.getBalanceAfter());
    }
}

