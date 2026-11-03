package com.ledgerapi.ledger;

import com.ledgerapi.domain.*;
import com.ledgerapi.error.ConflictException;
import com.ledgerapi.error.NotFoundException;
import com.ledgerapi.error.ValidationException;
import com.ledgerapi.error.LedgerApiException.FieldError;
import com.ledgerapi.messaging.OutboxEvent;
import com.ledgerapi.messaging.OutboxEventRepository;
import com.ledgerapi.messaging.TransactionRecordedPayload;
import com.ledgerapi.payments.ProcessedPayment;
import com.ledgerapi.payments.ProcessedPaymentRepository;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Validates and records transactions, and reverses them, on top of opening/reading accounts.
 * This is the single place money moves: every entry point in the application (REST, and the
 * payment-settled consumer) goes through here.
 *
 * <p>Accounts touched by a transaction are locked in a single global order (sorted by id) before
 * any balance is read or changed, so two transactions that touch the same accounts in opposite
 * order (A->B and B->A) always acquire their locks in the same sequence and can never deadlock.
 * Every check in {@link #record} runs before anything is persisted, so a refused transaction
 * leaves nothing behind - either every entry is written, or none of them are.
 *
 * <p>Every recorded transaction is written to the transactional outbox in the very same database
 * transaction that records it, so "recorded" and "will be announced" can never disagree; see
 * {@link com.ledgerapi.messaging.OutboxPublisher} for how it is actually sent to Kafka.
 */
@Slf4j
@Service
public class LedgerService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final EntryRepository entryRepository;
    private final ProcessedPaymentRepository processedPaymentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public LedgerService(AccountRepository accountRepository,
                          TransactionRepository transactionRepository,
                          EntryRepository entryRepository,
                          ProcessedPaymentRepository processedPaymentRepository,
                          OutboxEventRepository outboxEventRepository,
                          ObjectMapper objectMapper) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.processedPaymentRepository = processedPaymentRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    public record EntryCommand(String accountId, Direction direction, BigDecimal amount) {
    }

    /**
     * Opens a new account. The id is caller-supplied (e.g. "merchant-42") so it can be used as a
     * stable external reference from day one. An absent overdraft limit defaults to zero, meaning
     * the balance can never go negative.
     */
    @Transactional
    public Account openAccount(String id, String currencyCode, BigDecimal overdraftLimit) {
        if (id == null || id.isBlank()) {
            throw new ValidationException("MISSING_ACCOUNT_ID", "Account id is required");
        }
        CurrencyCode currency;
        try {
            currency = CurrencyCode.fromIsoCode(currencyCode);
        } catch (CurrencyCode.NotIso4217CurrencyException e) {
            throw new ValidationException("NOT_ISO_4217_CURRENCY", e.getMessage());
        } catch (CurrencyCode.UnsupportedCurrencyException e) {
            throw new ValidationException("UNSUPPORTED_CURRENCY", e.getMessage());
        }

        BigDecimal limit = overdraftLimit == null ? BigDecimal.ZERO.setScale(currency.decimalPlaces()) : overdraftLimit;
        if (limit.signum() < 0) {
            throw new ValidationException("INVALID_OVERDRAFT_LIMIT", "Overdraft limit cannot be negative");
        }
        int effectiveScale = Math.max(0, limit.stripTrailingZeros().scale());
        if (effectiveScale > currency.decimalPlaces()) {
            throw new ValidationException("AMOUNT_EXCEEDS_CURRENCY_DECIMALS",
                    currency + " has no more than " + currency.decimalPlaces() + " decimal place(s)");
        }

        if (accountRepository.existsById(id)) {
            throw new ConflictException("ACCOUNT_ALREADY_EXISTS", "Account " + id + " already exists");
        }

        Account account = new Account(id, currency, limit);
        accountRepository.save(account);
        log.info("Opened account {} ({}, overdraft limit {})", id, currency, limit);
        return account;
    }

    @Transactional(readOnly = true)
    public Account getAccount(String id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("ACCOUNT_NOT_FOUND", "No account with id " + id));
    }

    // --- Transactions and reversals ---

    @Transactional(readOnly = true)
    public Transaction getTransaction(String id) {
        Transaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("TRANSACTION_NOT_FOUND", "No transaction with id " + id));
        Hibernate.initialize(transaction.getEntries());
        return transaction;
    }

    /** An account's entries, newest first, paged, each with the balance after it. */
    @Transactional(readOnly = true)
    public Page<Entry> getEntries(String accountId, Instant asOf, Pageable pageable) {
        if (!accountRepository.existsById(accountId)) {
            throw new NotFoundException("ACCOUNT_NOT_FOUND", "No account with id " + accountId);
        }
        return asOf == null
                ? entryRepository.findByAccountIdOrderByNewestFirst(accountId, pageable)
                : entryRepository.findAsOf(accountId, asOf, pageable);
    }

    @Transactional
    public Transaction recordTransaction(String description, List<EntryCommand> entryCommands, String requestId) {
        return record(description, entryCommands, null, requestId);
    }

    @Transactional
    public Transaction reverseTransaction(String transactionId, String requestId) {
        Transaction original = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new NotFoundException("TRANSACTION_NOT_FOUND", "No transaction with id " + transactionId));
        if (original.isReversal()) {
            throw new ValidationException("CANNOT_REVERSE_A_REVERSAL", "Transaction " + transactionId + " is itself a reversal and cannot be reversed");
        }
        if (transactionRepository.existsByReversalOfId(transactionId)) {
            throw new ConflictException("ALREADY_REVERSED", "Transaction " + transactionId + " has already been reversed");
        }

        List<Entry> originalEntries = original.getEntries();
        List<EntryCommand> flipped = new ArrayList<>(originalEntries.size());
        for (Entry e : originalEntries) {
            Direction flippedDirection = e.getDirection() == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT;
            flipped.add(new EntryCommand(e.getAccountId(), flippedDirection, e.getAmount()));
        }
        try {
            return record("Reversal of " + transactionId, flipped, transactionId, requestId);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Unique constraint on reversal_of_id: a concurrent reversal attempt won the race.
            throw new ConflictException("ALREADY_REVERSED", "Transaction " + transactionId + " has already been reversed");
        }
    }

    private Transaction record(String description, List<EntryCommand> entryCommands, String reversalOfId, String requestId) {
        if (entryCommands == null || entryCommands.size() < 2) {
            throw new ValidationException("MIN_TWO_ENTRIES", "A transaction needs at least two entries");
        }
        List<FieldError> positivityErrors = new ArrayList<>();
        for (int i = 0; i < entryCommands.size(); i++) {
            EntryCommand c = entryCommands.get(i);
            if (c.amount() == null || c.amount().signum() <= 0) {
                positivityErrors.add(new FieldError("entries[" + i + "].amount", "Amount must be positive"));
            }
        }
        if (!positivityErrors.isEmpty()) {
            throw new ValidationException("AMOUNT_NOT_POSITIVE", "Every entry amount must be positive", positivityErrors);
        }

        // Lock every distinct account in a globally consistent order before reading any balance.
        SortedSet<String> accountIds = new TreeSet<>();
        entryCommands.forEach(c -> accountIds.add(c.accountId()));

        Map<String, Account> accounts = new LinkedHashMap<>();
        List<FieldError> unknown = new ArrayList<>();
        for (String accountId : accountIds) {
            accountRepository.lockById(accountId).ifPresentOrElse(
                    a -> accounts.put(accountId, a),
                    () -> unknown.add(new FieldError("accountId", "Unknown account: " + accountId)));
        }
        if (!unknown.isEmpty()) {
            throw new ValidationException("UNKNOWN_ACCOUNT", "One or more accounts do not exist", unknown);
        }

        CurrencyCode currency = accounts.values().iterator().next().getCurrency();
        boolean mixedCurrency = accounts.values().stream().anyMatch(a -> a.getCurrency() != currency);
        if (mixedCurrency) {
            throw new ValidationException("CURRENCY_MISMATCH", "All accounts in a transaction must be in the same currency");
        }

        List<FieldError> scaleErrors = new ArrayList<>();
        for (int i = 0; i < entryCommands.size(); i++) {
            BigDecimal amount = entryCommands.get(i).amount();
            int effectiveScale = Math.max(0, amount.stripTrailingZeros().scale());
            if (effectiveScale > currency.decimalPlaces()) {
                scaleErrors.add(new FieldError("entries[" + i + "].amount",
                        currency + " has no more than " + currency.decimalPlaces() + " decimal place(s)"));
            }
        }
        if (!scaleErrors.isEmpty()) {
            throw new ValidationException("AMOUNT_EXCEEDS_CURRENCY_DECIMALS", "An amount has more decimal places than its currency allows", scaleErrors);
        }

        BigDecimal totalDebits = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;
        for (EntryCommand c : entryCommands) {
            if (c.direction() == Direction.DEBIT) totalDebits = totalDebits.add(c.amount());
            else totalCredits = totalCredits.add(c.amount());
        }
        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new ValidationException("ENTRIES_NOT_BALANCED", "Debits (" + totalDebits + ") and credits (" + totalCredits + ") don't balance");
        }

        Map<String, BigDecimal> netDelta = new HashMap<>();
        for (EntryCommand c : entryCommands) {
            BigDecimal delta = c.direction() == Direction.CREDIT ? c.amount() : c.amount().negate();
            netDelta.merge(c.accountId(), delta, BigDecimal::add);
        }

        List<FieldError> overdraftErrors = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : netDelta.entrySet()) {
            Account account = accounts.get(e.getKey());
            BigDecimal projected = account.projectedBalance(e.getValue());
            if (account.wouldExceedOverdraft(projected)) {
                overdraftErrors.add(new FieldError("accountId", "Account " + account.getId() + " would reach " + projected
                        + ", past its overdraft limit of " + account.getOverdraftLimit()));
            }
        }
        if (!overdraftErrors.isEmpty()) {
            throw new ValidationException("OVERDRAFT_LIMIT_EXCEEDED", "One or more accounts would exceed their overdraft limit", overdraftErrors);
        }

        // Every check passed: apply all deltas and persist the transaction atomically.
        Transaction transaction = new Transaction(description, reversalOfId, requestId);
        Map<String, BigDecimal> running = new HashMap<>();
        accounts.forEach((id, a) -> running.put(id, a.getBalance()));
        for (EntryCommand c : entryCommands) {
            BigDecimal delta = c.direction() == Direction.CREDIT ? c.amount() : c.amount().negate();
            BigDecimal newBalance = running.get(c.accountId()).add(delta);
            running.put(c.accountId(), newBalance);
            transaction.addEntry(new Entry(c.accountId(), c.direction(), c.amount(), newBalance));
        }
        netDelta.forEach((id, delta) -> accounts.get(id).applyDelta(delta));

        transactionRepository.save(transaction);
        publishOutbox(transaction, requestId);

        log.info("Recorded transaction {} ({}), {} entries, requestId={}", transaction.getId(), description,
                entryCommands.size(), requestId);
        return transaction;
    }

    private void publishOutbox(Transaction transaction, String requestId) {
        TransactionRecordedPayload payload = TransactionRecordedPayload.from(transaction);
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize transaction-recorded event", e);
        }
        outboxEventRepository.save(new OutboxEvent("Transaction", transaction.getId(), "TransactionRecorded", json, requestId));
    }

    // --- Payment-settled messages (consumed from Kafka; see PaymentSettledConsumer) ---

    private static final Map<CurrencyCode, String> SETTLEMENT_ACCOUNT_IDS = Map.of(
            CurrencyCode.EUR, "settlement-eur",
            CurrencyCode.USD, "settlement-usd",
            CurrencyCode.JPY, "settlement-jpy");

    /**
     * Records the transaction for one settled payment: debit the settlement account for the
     * given currency, credit the merchant's account. Idempotent on {@code paymentId} - the same
     * payment, however many times it is redelivered (even under a brand-new event id), is
     * recorded at most once. Returns {@code true} if a new transaction was recorded, {@code false}
     * if this payment had already been processed.
     *
     * <p>The row that claims {@code paymentId} in {@code processed_payment} is inserted in the
     * very same transaction as the ledger entries, and that table has {@code payment_id} as its
     * primary key - so if two deliveries of the same payment race each other, only one commits;
     * the loser's unique-constraint violation is caught here and treated as "already processed".
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

        List<EntryCommand> entries = List.of(
                new EntryCommand(settlementAccountId, Direction.DEBIT, amount),
                new EntryCommand(merchantAccountId, Direction.CREDIT, amount));
        Transaction transaction = record("Payment " + paymentId + " settled", entries, null, eventId);
        try {
            processedPaymentRepository.saveAndFlush(new ProcessedPayment(paymentId, eventId, transaction.getId()));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // A concurrent delivery of the same payment won the race; our transaction rolls back
            // (the ledger entries above are undone with it) and the caller treats this as "already processed".
            throw e;
        }
        return true;
    }
}

