package com.ledgerapi.idempotency;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.ledgerapi.error.ApiExceptionMapper;
import com.ledgerapi.error.IdempotencyKeyReusedException;
import com.ledgerapi.error.LedgerApiException;
import com.ledgerapi.error.RequestInProgressException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Makes mutating endpoints safe to retry.
 *
 * <p>A client supplies an {@code Idempotency-Key} header. The key, scoped to the endpoint, is
 * claimed in its own transaction (committed immediately, before any business logic runs) so that
 * two concurrent copies of the same request - even landing on different instances - race on a
 * single database unique-key insert instead of racing on business state. Whichever one loses the
 * race either waits for the winner's cached answer (if retried later) or is told the request is
 * already in progress. Once the winner finishes, its exact response (success or business error)
 * is cached and replayed verbatim to every later retry with the same key and the same request
 * body. A key reused with a different body is rejected outright: the ledger never mistakes a
 * different request for a retry.
 */
@Slf4j
@Service
public class IdempotencyService {


    private final IdempotencyRecordRepository repository;
    private final ObjectMapper objectMapper;
    private final ApiExceptionMapper exceptionMapper;
    private final TransactionTemplate requiresNew;

    public IdempotencyService(IdempotencyRecordRepository repository,
                               ObjectMapper objectMapper,
                               ApiExceptionMapper exceptionMapper,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.exceptionMapper = exceptionMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehaviorName("PROPAGATION_REQUIRES_NEW");
    }

    public static String hash(String rawBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawBody.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public ResponseEntity<Object> execute(String scope, String idempotencyKey, String requestBody,
                                           Supplier<ResponseEntity<?>> businessLogic) {
        String recordId = scope + ":" + idempotencyKey;
        String requestHash = hash(requestBody);

        boolean claimed = tryClaim(recordId, requestHash);
        if (!claimed) {
            return replayOrReject(recordId, requestHash);
        }

        ResponseEntity<?> response;
        try {
            response = businessLogic.get();
        } catch (LedgerApiException ex) {
            response = exceptionMapper.toResponseEntity(ex);
        } catch (RuntimeException ex) {
            log.error("Unexpected error processing idempotent request {}", recordId, ex);
            // Do not cache infrastructure failures: let the retry actually re-attempt the work.
            deleteClaim(recordId);
            throw ex;
        }
        persistCompletion(recordId, response);
        @SuppressWarnings("unchecked")
        ResponseEntity<Object> result = (ResponseEntity<Object>) response;
        return result;
    }

    private boolean tryClaim(String recordId, String requestHash) {
        try {
            return Boolean.TRUE.equals(requiresNew.execute(status -> {
                repository.saveAndFlush(new IdempotencyRecord(recordId, requestHash));
                return true;
            }));
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    private void deleteClaim(String recordId) {
        requiresNew.executeWithoutResult(status -> repository.deleteById(recordId));
    }

    private void persistCompletion(String recordId, ResponseEntity<?> response) {
        String body = serialize(response.getBody());
        requiresNew.executeWithoutResult(status -> {
            IdempotencyRecord record = repository.findById(recordId).orElseThrow();
            record.complete(response.getStatusCode().value(), body);
        });
    }

    private ResponseEntity<Object> replayOrReject(String recordId, String requestHash) {
        Optional<IdempotencyRecord> existing = repository.findById(recordId);
        if (existing.isEmpty()) {
            // Lost the race but the winner hasn't committed its claim-read snapshot yet; ask for a retry.
            throw new RequestInProgressException(recordId);
        }
        IdempotencyRecord record = existing.get();
        if (!record.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException(recordId);
        }
        if (record.getStatus() == IdempotencyRecord.Status.IN_PROGRESS) {
            throw new RequestInProgressException(recordId);
        }
        JsonNode body = deserialize(record.getResponseBody());
        return ResponseEntity.status(record.getResponseStatus()).body(body);
    }

    private String serialize(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize idempotent response", e);
        }
    }

    private JsonNode deserialize(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Could not deserialize cached idempotent response", e);
        }
    }
}

