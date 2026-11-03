package com.ledgerapi.domain;

import java.util.Currency;
import java.util.Set;

/**
 * Currencies supported by the ledger, with the decimal places ISO 4217 defines for them.
 */
public enum CurrencyCode {
    EUR(2),
    USD(2),
    JPY(0);

    private final int decimalPlaces;

    CurrencyCode(int decimalPlaces) {
        this.decimalPlaces = decimalPlaces;
    }

    public int decimalPlaces() {
        return decimalPlaces;
    }

    private static final Set<String> SUPPORTED_CODES = Set.of("EUR", "USD", "JPY");

    /**
     * @throws NotIso4217CurrencyException if the code is not a valid ISO 4217 currency code at all
     * @throws UnsupportedCurrencyException if it is a valid ISO 4217 code, but not one the ledger supports
     */
    public static CurrencyCode fromIsoCode(String code) {
        if (code == null || code.isBlank()) {
            throw new NotIso4217CurrencyException(code);
        }
        String normalized = code.trim().toUpperCase();
        try {
            // java.util.Currency knows every ISO 4217 code, which lets us distinguish
            // "not an ISO code at all" from "a valid ISO code we don't support".
            Currency.getInstance(normalized);
        } catch (IllegalArgumentException e) {
            throw new NotIso4217CurrencyException(normalized);
        }
        if (!SUPPORTED_CODES.contains(normalized)) {
            throw new UnsupportedCurrencyException(normalized);
        }
        return CurrencyCode.valueOf(normalized);
    }

    public static class NotIso4217CurrencyException extends RuntimeException {
        public NotIso4217CurrencyException(String code) {
            super("'" + code + "' is not an ISO 4217 currency code");
        }
    }

    public static class UnsupportedCurrencyException extends RuntimeException {
        public UnsupportedCurrencyException(String code) {
            super("'" + code + "' is a valid ISO 4217 code, but not a currency the ledger supports");
        }
    }
}

