package com.ledgerapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code ledger.*} configuration tree (see application.yaml): which Kafka topics to
 * use, and how the outbox poller is paced.
 */
@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(Topics topics, Outbox outbox) {

    public record Topics(String paymentSettled, String paymentSettledDlq, String transactionRecorded) {
    }

    public record Outbox(long pollDelayMs, int batchSize) {
    }
}

