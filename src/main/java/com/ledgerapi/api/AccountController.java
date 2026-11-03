package com.ledgerapi.api;

import com.ledgerapi.api.dto.AccountResponse;
import com.ledgerapi.api.dto.EntryPageItem;
import com.ledgerapi.api.dto.OpenAccountRequest;
import com.ledgerapi.domain.Account;
import com.ledgerapi.error.ValidationException;
import com.ledgerapi.idempotency.IdempotencyService;
import com.ledgerapi.ledger.LedgerService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;

/**
 * Opening accounts, reading their balance, and listing their entries. Versioned under
 * {@code /v1}: a breaking change to the request/response shape would go under {@code /v2},
 * leaving existing clients on {@code /v1} unaffected.
 *
 * <p>Opening an account requires an {@code Idempotency-Key} header: a client that doesn't get an
 * answer can safely resend the exact same request, and gets back exactly the response the first
 * attempt produced (see {@link IdempotencyService}).
 */
@RestController
@RequestMapping("/v1/accounts")
public class AccountController {

    private final LedgerService ledgerService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public AccountController(LedgerService ledgerService, IdempotencyService idempotencyService, ObjectMapper objectMapper) {
        this.ledgerService = ledgerService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> openAccount(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                               @RequestBody String rawBody) {
        return idempotencyService.execute("open-account", idempotencyKey, rawBody, () -> {
            OpenAccountRequest request = parse(rawBody, OpenAccountRequest.class);
            Account account = ledgerService.openAccount(request.id(), request.currency(), request.overdraftLimit());
            return ResponseEntity.created(URI.create("/v1/accounts/" + account.getId()))
                    .body(AccountResponse.from(account));
        });
    }

    @GetMapping("/{id}")
    public AccountResponse getAccount(@PathVariable String id) {
        return AccountResponse.from(ledgerService.getAccount(id));
    }

    /** Newest first, paged, each entry with the balance it left the account at. */
    @GetMapping("/{id}/entries")
    public Page<EntryPageItem> getEntries(@PathVariable String id,
                                           @RequestParam(required = false) Instant asOf,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        int pageSize = Math.min(Math.max(size, 1), 200);
        return ledgerService.getEntries(id, asOf, PageRequest.of(Math.max(page, 0), pageSize))
                .map(EntryPageItem::from);
    }

    private <T> T parse(String rawBody, Class<T> type) {
        try {
            return objectMapper.readValue(rawBody, type);
        } catch (Exception e) {
            throw new ValidationException("MALFORMED_REQUEST_BODY", "The request body could not be parsed: " + e.getMessage());
        }
    }
}

