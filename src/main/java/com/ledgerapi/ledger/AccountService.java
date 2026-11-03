package com.ledgerapi.ledger;

import com.ledgerapi.domain.*;
import com.ledgerapi.error.ConflictException;
import com.ledgerapi.error.NotFoundException;
import com.ledgerapi.error.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Opening and reading accounts, and listing their entries. Does not move money itself - see
 * {@link TransactionService} for that - so account-opening validation (currency, overdraft limit)
 * lives here, alongside the account repository, rather than spread across the service that
 * records transactions.
 */
@Slf4j
@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final EntryRepository entryRepository;

    public AccountService(AccountRepository accountRepository, EntryRepository entryRepository) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
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
}

