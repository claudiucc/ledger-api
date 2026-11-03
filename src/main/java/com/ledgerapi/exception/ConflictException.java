package com.ledgerapi.error;

import org.springframework.http.HttpStatus;

public class ConflictException extends LedgerApiException {
    public ConflictException(String errorCode, String message) {
        super(HttpStatus.CONFLICT, errorCode, message);
    }
}

