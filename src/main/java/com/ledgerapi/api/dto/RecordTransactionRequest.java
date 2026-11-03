package com.ledgerapi.api.dto;

import java.math.BigDecimal;
import java.util.List;

/** Request body for {@code POST /v1/transactions}. */
public record RecordTransactionRequest(String description, List<EntryRequest> entries) {

    public record EntryRequest(String accountId, String direction, BigDecimal amount) {
    }
}

