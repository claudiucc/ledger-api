package com.ledgerapi.error;

import org.springframework.http.HttpStatus;

public class NotFoundException extends LedgerApiException {
    public NotFoundException(String errorCode, String message) {
        super(HttpStatus.NOT_FOUND, errorCode, message);
    }
}

