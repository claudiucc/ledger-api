package com.ledgerapi.ledger;

import com.ledgerapi.domain.CurrencyCode;
import com.ledgerapi.domain.Direction;
import com.ledgerapi.domain.Transaction;
import com.ledgerapi.error.ValidationException;
import com.ledgerapi.payments.ProcessedPayment;
import com.ledgerapi.payments.ProcessedPaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Turns a payment-settled message (see {@link com.ledgerapi.messaging.PaymentSettledConsumer})
 * into a ledger transaction: debit the settlement account for the message's currency, credit the
 * merchant's account - built on top of {@link TransactionService} rather than duplicating its
 * validation, locking and outbox-publishing.
 */
@Slf4j
@Service
public class PaymentSettlementService {

    private static final Map<CurrencyCode, String> SETTLEMENT_ACCOUNT_IDS = Map.of(
            CurrencyCode.EUR, "settlement-eur",
            CurrencyCode.USD, "settlement-usd",
            CurrencyCode.JPY, "settlement-jpy");

    private final TransactionService transactionService;
    private final ProcessedPaymentRepository processedPaymentRepository;

    public PaymentSettlementService(TransactionService transactionService,
                                     ProcessedPaymentRepository processedPaymentRepository) {
        this.transactionService = transactionService;
        this.processedPaymentRepository = processedPaymentRepository;
    }

    /**
     * Records the transaction for one settled payment. Idempotent on {@code paymentId} - the same
     * payment, however many times it is redelivered (even under a brand-new event id), is
     * recorded at most once. Returns {@code true} if a new transaction was recorded, {@code false}
     * if this payment had already been processed.
     *
     * <p>The row that claims {@code paymentId} in {@code processed_payment} is inserted in the
     * very same transaction as the ledger entries (via {@link TransactionService#recordTransaction},
     * which joins this method's transaction rather than starting its own), and that table has
     * {@code payment_id} as its primary key - so if two deliveries of the same payment race each
     * other, only one commits; the loser's unique-constraint violation is caught here and treated
     * as "already processed".
     */
    @Transactional
    public boolean recordPaymentSettled(String eventId, String paymentId, String merchantAccountId,
                                         BigDecimal amount, String currencyCode) {
        if (processedPaymentRepository.existsById(paymentId)) {
            log.info("Payment {} (event {}) already processed; skipping redelivery", paymentId, eventId);
            return false;
        }
        CurrencyCode currency;
        try {
            currency = CurrencyCode.fromIsoCode(currencyCode);
        } catch (CurrencyCode.NotIso4217CurrencyException e) {
            throw new ValidationException("NOT_ISO_4217_CURRENCY", e.getMessage());
        } catch (CurrencyCode.UnsupportedCurrencyException e) {
            throw new ValidationException("UNSUPPORTED_CURRENCY", e.getMessage());
        }
        String settlementAccountId = SETTLEMENT_ACCOUNT_IDS.get(currency);

        List<TransactionService.EntryCommand> entries = List.of(
                new TransactionService.EntryCommand(settlementAccountId, Direction.DEBIT, amount),
                new TransactionService.EntryCommand(merchantAccountId, Direction.CREDIT, amount));
        Transaction transaction = transactionService.recordTransaction("Payment " + paymentId + " settled", entries, eventId);
        processedPaymentRepository.saveAndFlush(new ProcessedPayment(paymentId, eventId, transaction.getId()));
        return true;
    }
}

