package com.ledgerapi.messaging;

import java.math.BigDecimal;

/**
 * The JSON shape of a payment-settled message, as sent by the payment system: an event id (for
 * broker-level deduplication, which the ledger does not rely on - see {@link PaymentSettledConsumer}),
 * a payment id (what the ledger actually dedupes on, so redelivery under a brand-new event id is
 * still recognised), the merchant's account id, an amount and a currency.
 */
public record PaymentSettledPayload(String eventId, String paymentId, String accountId, BigDecimal amount, String currency) {
}

