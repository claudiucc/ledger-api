package com.ledgerapi.error;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * Base type for every error the ledger refuses a request with. Carries a stable machine-readable
 * {@code errorCode}, the HTTP status to answer with, and optionally which part of the request
 * was at fault, so API clients can both branch on the code and show the detail to a human.
 */
public class LedgerApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final List<FieldError> fieldErrors;

    public LedgerApiException(HttpStatus status, String errorCode, String message) {
        this(status, errorCode, message, List.of());
    }

    public LedgerApiException(HttpStatus status, String errorCode, String message, List<FieldError> fieldErrors) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.fieldErrors = fieldErrors;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public List<FieldError> getFieldErrors() {
        return fieldErrors;
    }

    public record FieldError(String pointer, String message) {
    }
}

