package com.ledgerapi.error;

import org.springframework.http.HttpStatus;

import java.util.List;

public class ValidationException extends LedgerApiException {
    public ValidationException(String errorCode, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, message);
    }

    public ValidationException(String errorCode, String message, List<FieldError> fieldErrors) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, message, fieldErrors);
    }
}

