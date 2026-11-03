package com.ledgerapi.error;

import org.springframework.http.HttpStatus;

/** An identical request (same Idempotency-Key) is already being processed by another thread/instance. */
public class RequestInProgressException extends LedgerApiException {
    public RequestInProgressException(String key) {
        super(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
                "A request with Idempotency-Key '" + key + "' is already being processed; retry shortly");
    }
}

