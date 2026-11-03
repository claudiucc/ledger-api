package com.ledgerapi.messaging;

import com.ledgerapi.config.LedgerProperties;
import com.ledgerapi.error.LedgerApiException;
import com.ledgerapi.ledger.PaymentSettlementService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes payment-settled messages and turns each one into a ledger transaction (see
 * {@link PaymentSettlementService#recordPaymentSettled}).
 *
 * <p>Redelivery: idempotent on {@code paymentId}, not on {@code eventId} - the same payment,
 * resent under a brand-new event id, is still recorded at most once (see
 * {@code processed_payment}).
 *
 * <p>A message that cannot be recorded for a business reason (malformed JSON, an unknown field,
 * an unknown account, the wrong currency) is published to the dead-letter topic together with the
 * reason, logged, and the offset is committed - so it is never lost silently and it never blocks
 * the messages behind it. A message that fails for an infrastructure reason (e.g. the database is
 * briefly unavailable) is left unacknowledged and redelivered by Kafka's normal retry/backoff.
 */
@Slf4j
@Component
public class PaymentSettledConsumer {

    private final PaymentSettlementService paymentSettlementService;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final LedgerProperties properties;

    public PaymentSettledConsumer(PaymentSettlementService paymentSettlementService, ObjectMapper objectMapper,
                                   KafkaTemplate<String, String> kafkaTemplate, LedgerProperties properties) {
        this.paymentSettlementService = paymentSettlementService;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @KafkaListener(topics = "${ledger.topics.payment-settled}", groupId = "ledger-api")
    public void onMessage(String rawPayload) {
        PaymentSettledPayload payload;
        try {
            payload = objectMapper.readValue(rawPayload, PaymentSettledPayload.class);
        } catch (Exception e) {
            deadLetter(rawPayload, "MALFORMED_MESSAGE", e.getMessage());
            return;
        }
        MDC.put("requestId", "payment:" + payload.eventId());
        try {
            if (payload.paymentId() == null || payload.paymentId().isBlank()
                    || payload.accountId() == null || payload.accountId().isBlank()
                    || payload.amount() == null || payload.currency() == null) {
                deadLetter(rawPayload, "MALFORMED_MESSAGE", "paymentId, accountId, amount and currency are all required");
                return;
            }
            boolean recorded = paymentSettlementService.recordPaymentSettled(
                    payload.eventId(), payload.paymentId(), payload.accountId(), payload.amount(), payload.currency());
            log.info("Payment {} (event {}): {}", payload.paymentId(), payload.eventId(),
                    recorded ? "recorded" : "already processed, skipped");
        } catch (LedgerApiException e) {
            deadLetter(rawPayload, e.getErrorCode(), e.getMessage());
        } catch (DataIntegrityViolationException e) {
            log.info("Payment {} (event {}) lost a concurrent redelivery race; already processed", payload.paymentId(), payload.eventId());
        } finally {
            MDC.remove("requestId");
        }
    }

    private void deadLetter(String rawPayload, String reason, String detail) {
        log.warn("Payment-settled message could not be recorded ({}: {}); routing to DLQ: {}", reason, detail, rawPayload);
        kafkaTemplate.send(properties.topics().paymentSettledDlq(), rawPayload);
    }
}


