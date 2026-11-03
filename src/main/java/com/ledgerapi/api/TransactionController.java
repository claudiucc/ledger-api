package com.ledgerapi.api;

import com.ledgerapi.api.dto.RecordTransactionRequest;
import com.ledgerapi.api.dto.TransactionResponse;
import com.ledgerapi.domain.Direction;
import com.ledgerapi.domain.Transaction;
import com.ledgerapi.error.ValidationException;
import com.ledgerapi.idempotency.IdempotencyService;
import com.ledgerapi.ledger.TransactionService;
import com.ledgerapi.web.RequestIdFilter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.List;

/**
 * Recording transactions directly through the API, reading them back, and reversing them.
 * Versioned under {@code /v1}, alongside {@link AccountController}.
 *
 * <p>Recording and reversing both require an {@code Idempotency-Key} header, scoped to the
 * endpoint (and, for reversals, to the transaction id too): a retried request is never recorded
 * twice, and gets back exactly the response the first attempt produced (see
 * {@link IdempotencyService}).
 */
@RestController
@RequestMapping("/v1/transactions")
public class TransactionController {

    private final TransactionService transactionService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public TransactionController(TransactionService transactionService, IdempotencyService idempotencyService, ObjectMapper objectMapper) {
        this.transactionService = transactionService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> recordTransaction(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                      @RequestBody String rawBody) {
        return idempotencyService.execute("record-transaction", idempotencyKey, rawBody, () -> {
            RecordTransactionRequest request = parse(rawBody, RecordTransactionRequest.class);
            List<TransactionService.EntryCommand> commands = toCommands(request);
            Transaction transaction = transactionService.recordTransaction(
                    request.description(), commands, RequestIdFilter.currentRequestId());
            return ResponseEntity.created(URI.create("/v1/transactions/" + transaction.getId()))
                    .body(TransactionResponse.from(transaction));
        });
    }

    @GetMapping("/{id}")
    public TransactionResponse getTransaction(@PathVariable String id) {
        return TransactionResponse.from(transactionService.getTransaction(id));
    }

    @PostMapping("/{id}/reversals")
    public ResponseEntity<Object> reverseTransaction(@PathVariable String id,
                                                       @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return idempotencyService.execute("reverse-transaction:" + id, idempotencyKey, id, () -> {
            Transaction reversal = transactionService.reverseTransaction(id, RequestIdFilter.currentRequestId());
            return ResponseEntity.created(URI.create("/v1/transactions/" + reversal.getId()))
                    .body(TransactionResponse.from(reversal));
        });
    }

    private <T> T parse(String rawBody, Class<T> type) {
        try {
            return objectMapper.readValue(rawBody, type);
        } catch (Exception e) {
            throw new ValidationException("MALFORMED_REQUEST_BODY", "The request body could not be parsed: " + e.getMessage());
        }
    }

    private List<TransactionService.EntryCommand> toCommands(RecordTransactionRequest request) {
        if (request.entries() == null) {
            throw new ValidationException("MIN_TWO_ENTRIES", "A transaction needs at least two entries");
        }
        return request.entries().stream()
                .map(e -> new TransactionService.EntryCommand(e.accountId(), parseDirection(e.direction()), e.amount()))
                .toList();
    }

    private Direction parseDirection(String raw) {
        if (raw == null) {
            throw new ValidationException("MISSING_DIRECTION", "Every entry needs a direction: DEBIT or CREDIT");
        }
        try {
            return Direction.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ValidationException("INVALID_DIRECTION", "'" + raw + "' is not DEBIT or CREDIT");
        }
    }
}

