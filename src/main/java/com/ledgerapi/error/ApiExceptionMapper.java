package com.ledgerapi.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Builds the RFC 7807 problem+json body for a {@link LedgerApiException}. Shared between the
 * {@code @RestControllerAdvice} (for requests outside the idempotency wrapper) and the
 * idempotency service (which must cache the exact error response a retry should get back).
 */
@Component
public class ApiExceptionMapper {

    public ResponseEntity<ProblemDetail> toResponseEntity(LedgerApiException ex) {
        ProblemDetail problem = toProblemDetail(ex);
        return ResponseEntity.status(ex.getStatus()).body(problem);
    }

    public ProblemDetail toProblemDetail(LedgerApiException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setType(URI.create("https://ledger-api.example.com/errors/" + ex.getErrorCode()));
        problem.setTitle(ex.getErrorCode());
        problem.setProperty("errorCode", ex.getErrorCode());
        if (!ex.getFieldErrors().isEmpty()) {
            problem.setProperty("errors", ex.getFieldErrors());
        }
        return problem;
    }

    public ProblemDetail generic(HttpStatus status, String errorCode, String message) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message);
        problem.setTitle(errorCode);
        problem.setProperty("errorCode", errorCode);
        return problem;
    }
}

