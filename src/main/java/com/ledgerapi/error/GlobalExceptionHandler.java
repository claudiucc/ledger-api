package com.ledgerapi.error;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final ApiExceptionMapper mapper;

    public GlobalExceptionHandler(ApiExceptionMapper mapper) {
        this.mapper = mapper;
    }

    @ExceptionHandler(LedgerApiException.class)
    public ProblemDetail handleLedgerApiException(LedgerApiException ex) {
        return mapper.toProblemDetail(ex);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(MissingRequestHeaderException ex) {
        return mapper.generic(HttpStatus.BAD_REQUEST, "MISSING_HEADER", ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
        return mapper.generic(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST_BODY", "The request body could not be parsed");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleInvalid(MethodArgumentNotValidException ex) {
        List<LedgerApiException.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new LedgerApiException.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        ProblemDetail problem = mapper.generic(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "Request failed validation");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        return mapper.generic(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        return mapper.generic(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }
}

