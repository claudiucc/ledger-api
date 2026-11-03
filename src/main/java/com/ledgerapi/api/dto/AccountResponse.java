package com.ledgerapi.api.dto;

import com.ledgerapi.domain.Account;

import java.math.BigDecimal;

/** Response body for an account, including its current balance. */
public record AccountResponse(String id, String currency, BigDecimal overdraftLimit, BigDecimal balance) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getCurrency().name(),
                account.getOverdraftLimit(),
                account.getBalance());
    }
}

