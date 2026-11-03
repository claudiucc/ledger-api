package com.ledgerapi.error;

import org.springframework.http.HttpStatus;

/** The caller reused an Idempotency-Key with a request body that differs from the original. */
public class IdempotencyKeyReusedException extends LedgerApiException {
    public IdempotencyKeyReusedException(String key) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key '" + key + "' was already used for a different request");
    }
}

