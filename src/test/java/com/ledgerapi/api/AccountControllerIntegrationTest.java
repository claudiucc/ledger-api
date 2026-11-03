package com.ledgerapi.api;

import com.ledgerapi.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests for opening accounts and reading them back, against a real MySQL database
 * (see {@link AbstractIntegrationTest}) with the production Liquibase migration applied - the
 * same guarantee the "Accounts" section of the spec asks for: open an account, read it back with
 * its current balance.
 *
 * <p>Driven through {@link MockMvc} rather than a real HTTP client: it still exercises the whole
 * stack (filters, controller, service, JPA, the real MySQL schema and exception handling) without
 * depending on Spring Boot's split-out REST-client-test module.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AccountControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static String uniqueId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    @Test
    void opensAnAccountAndReadsItBackWithItsBalance() throws Exception {
        String id = uniqueId("merchant");

        mockMvc.perform(post("/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", uniqueId("key"))
                        .content("{\"id\":\"" + id + "\",\"currency\":\"EUR\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/v1/accounts/" + id))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.overdraftLimit").value(0))
                .andExpect(jsonPath("$.balance").value(0));

        mockMvc.perform(get("/v1/accounts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    void honoursAnExplicitOverdraftLimit() throws Exception {
        String id = uniqueId("merchant");

        mockMvc.perform(post("/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", uniqueId("key"))
                        .content("{\"id\":\"" + id + "\",\"currency\":\"USD\",\"overdraftLimit\":50.00}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.overdraftLimit").value(50.00));
    }

    @Test
    void supportsAZeroDecimalCurrency() throws Exception {
        String id = uniqueId("merchant");

        mockMvc.perform(post("/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", uniqueId("key"))
                        .content("{\"id\":\"" + id + "\",\"currency\":\"JPY\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("JPY"));
    }

    @Test
    void readingAnUnknownAccountReturns404() throws Exception {
        mockMvc.perform(get("/v1/accounts/{id}", uniqueId("does-not-exist")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void openingTheSameAccountIdTwiceIsRejected() throws Exception {
        String id = uniqueId("merchant");
        String body = "{\"id\":\"" + id + "\",\"currency\":\"EUR\"}";

        mockMvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", uniqueId("key")).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", uniqueId("key")).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_ALREADY_EXISTS"));
    }

    @Test
    void retryingTheSameRequestWithTheSameIdempotencyKeyReturnsTheSameAnswer() throws Exception {
        String id = uniqueId("merchant");
        String body = "{\"id\":\"" + id + "\",\"currency\":\"EUR\"}";
        String idempotencyKey = uniqueId("key");

        mockMvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id));

        // Same key, same body: replayed, not a conflict, even though the account now exists.
        mockMvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void reusingAnIdempotencyKeyForADifferentRequestIsRejected() throws Exception {
        String idempotencyKey = uniqueId("key");

        mockMvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .content("{\"id\":\"" + uniqueId("merchant") + "\",\"currency\":\"EUR\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", idempotencyKey)
                        .content("{\"id\":\"" + uniqueId("merchant") + "\",\"currency\":\"USD\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void rejectsAValidIso4217CodeTheLedgerDoesNotSupport() throws Exception {
        mockMvc.perform(post("/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", uniqueId("key"))
                        .content("{\"id\":\"" + uniqueId("acct") + "\",\"currency\":\"GBP\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_CURRENCY"));
    }

    @Test
    void rejectsACodeThatIsNotIso4217AtAll() throws Exception {
        mockMvc.perform(post("/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", uniqueId("key"))
                        .content("{\"id\":\"" + uniqueId("acct") + "\",\"currency\":\"ABC\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("NOT_ISO_4217_CURRENCY"));
    }

    @Test
    void rejectsANegativeOverdraftLimit() throws Exception {
        mockMvc.perform(post("/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", uniqueId("key"))
                        .content("{\"id\":\"" + uniqueId("acct") + "\",\"currency\":\"EUR\",\"overdraftLimit\":-1.00}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INVALID_OVERDRAFT_LIMIT"));
    }

    @Test
    void openingAnAccountWithoutAnIdempotencyKeyIsRejected() throws Exception {
        mockMvc.perform(post("/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + uniqueId("acct") + "\",\"currency\":\"EUR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MISSING_HEADER"));
    }

    @Test
    void theLedgersOwnSettlementAccountsAreSeededWithUnlimitedOverdraft() throws Exception {
        mockMvc.perform(get("/v1/accounts/settlement-eur"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.overdraftLimit").doesNotExist());
    }
}

