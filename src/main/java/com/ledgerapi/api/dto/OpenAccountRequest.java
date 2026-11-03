package com.ledgerapi.api.dto;

import java.math.BigDecimal;

/** Request body for {@code POST /v1/accounts}. */
public record OpenAccountRequest(String id, String currency, BigDecimal overdraftLimit) {
}

