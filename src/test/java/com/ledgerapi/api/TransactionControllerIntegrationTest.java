package com.ledgerapi.api;

import com.ledgerapi.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests for recording and reversing transactions, following the worked examples in
 * the spec: a payout with a fee (example 2), reversing it (example 3), and each of the refusal
 * cases in example 5.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TransactionControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static String uniqueId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private String openAccount(String currency, String overdraftLimitJson) throws Exception {
        String id = uniqueId("acct");
        String body = overdraftLimitJson == null
                ? "{\"id\":\"" + id + "\",\"currency\":\"" + currency + "\"}"
                : "{\"id\":\"" + id + "\",\"currency\":\"" + currency + "\",\"overdraftLimit\":" + overdraftLimitJson + "}";
        mockMvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", uniqueId("key")).content(body))
                .andExpect(status().isCreated());
        return id;
    }

    private String recordTransaction(String body) throws Exception {
        return mockMvc.perform(post("/v1/transactions").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", uniqueId("key")).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void recordsAPayoutWithAFeeAndReversesIt() throws Exception {
        // Example 2: Merchant 42 is paid out 98.50 EUR, with a fee of 1.50.
        String merchant = openAccount("EUR", null);
        String payouts = openAccount("EUR", null);
        String fees = openAccount("EUR", null);

        // Give the merchant a starting balance of 125.50 so the payout doesn't go past its (zero) overdraft limit.
        mockMvc.perform(post("/v1/transactions").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", uniqueId("key"))
                        .content("{\"description\":\"seed\",\"entries\":["
                                + "{\"accountId\":\"settlement-eur\",\"direction\":\"DEBIT\",\"amount\":125.50},"
                                + "{\"accountId\":\"" + merchant + "\",\"direction\":\"CREDIT\",\"amount\":125.50}]}"))
                .andExpect(status().isCreated());

        String payoutBody = "{\"description\":\"Payout\",\"entries\":["
                + "{\"accountId\":\"" + merchant + "\",\"direction\":\"DEBIT\",\"amount\":100.00},"
                + "{\"accountId\":\"" + payouts + "\",\"direction\":\"CREDIT\",\"amount\":98.50},"
                + "{\"accountId\":\"" + fees + "\",\"direction\":\"CREDIT\",\"amount\":1.50}]}";
        String response = recordTransaction(payoutBody);
        String transactionId = com.jayway.jsonpath.JsonPath.read(response, "$.id");

        mockMvc.perform(get("/v1/accounts/{id}", merchant)).andExpect(jsonPath("$.balance").value(25.50));
        mockMvc.perform(get("/v1/accounts/{id}", payouts)).andExpect(jsonPath("$.balance").value(98.50));
        mockMvc.perform(get("/v1/accounts/{id}", fees)).andExpect(jsonPath("$.balance").value(1.50));

        // Example 3: the payout is reversed.
        mockMvc.perform(post("/v1/transactions/{id}/reversals", transactionId)
                        .header("Idempotency-Key", uniqueId("key")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reversalOfId").value(transactionId));

        mockMvc.perform(get("/v1/accounts/{id}", merchant)).andExpect(jsonPath("$.balance").value(125.50));
        mockMvc.perform(get("/v1/accounts/{id}", payouts)).andExpect(jsonPath("$.balance").value(0.00));
        mockMvc.perform(get("/v1/accounts/{id}", fees)).andExpect(jsonPath("$.balance").value(0.00));

        // Reversing it a second time is refused.
        mockMvc.perform(post("/v1/transactions/{id}/reversals", transactionId)
                        .header("Idempotency-Key", uniqueId("key")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ALREADY_REVERSED"));

        // The original transaction itself is untouched and still readable.
        mockMvc.perform(get("/v1/transactions/{id}", transactionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transactionId));
    }

    @Test
    void refusesUnbalancedDebitsAndCredits() throws Exception {
        String a = openAccount("EUR", null);
        String b = openAccount("EUR", null);

        mockMvc.perform(post("/v1/transactions").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", uniqueId("key"))
                        .content("{\"description\":\"unbalanced\",\"entries\":["
                                + "{\"accountId\":\"" + a + "\",\"direction\":\"DEBIT\",\"amount\":100.00},"
                                + "{\"accountId\":\"" + b + "\",\"direction\":\"CREDIT\",\"amount\":99.00}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("ENTRIES_NOT_BALANCED"));
    }

    @Test
    void refusesATransactionThatWouldExceedAnOverdraftLimit() throws Exception {
        String a = openAccount("EUR", null); // overdraft limit zero
        String b = openAccount("EUR", null);

        mockMvc.perform(post("/v1/transactions").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", uniqueId("key"))
                        .content("{\"description\":\"overdraft\",\"entries\":["
                                + "{\"accountId\":\"" + a + "\",\"direction\":\"DEBIT\",\"amount\":50.00},"
                                + "{\"accountId\":\"" + b + "\",\"direction\":\"CREDIT\",\"amount\":50.00}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("OVERDRAFT_LIMIT_EXCEEDED"));

        // Nothing was recorded: both balances are untouched.
        mockMvc.perform(get("/v1/accounts/{id}", a)).andExpect(jsonPath("$.balance").value(0));
        mockMvc.perform(get("/v1/accounts/{id}", b)).andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    void refusesAnAmountWithMoreDecimalPlacesThanItsCurrencyAllows() throws Exception {
        String a = openAccount("JPY", null);
        String b = openAccount("JPY", null);

        mockMvc.perform(post("/v1/transactions").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", uniqueId("key"))
                        .content("{\"description\":\"fractional yen\",\"entries\":["
                                + "{\"accountId\":\"" + a + "\",\"direction\":\"DEBIT\",\"amount\":100.5},"
                                + "{\"accountId\":\"" + b + "\",\"direction\":\"CREDIT\",\"amount\":100.5}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("AMOUNT_EXCEEDS_CURRENCY_DECIMALS"));
    }

    @Test
    void refusesAccountsInDifferentCurrencies() throws Exception {
        String eur = openAccount("EUR", null);
        String jpy = openAccount("JPY", null);

        mockMvc.perform(post("/v1/transactions").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", uniqueId("key"))
                        .content("{\"description\":\"mismatch\",\"entries\":["
                                + "{\"accountId\":\"" + eur + "\",\"direction\":\"DEBIT\",\"amount\":10.00},"
                                + "{\"accountId\":\"" + jpy + "\",\"direction\":\"CREDIT\",\"amount\":10}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CURRENCY_MISMATCH"));
    }

    @Test
    void refusesATransactionWithOnlyOneEntry() throws Exception {
        String a = openAccount("EUR", null);

        mockMvc.perform(post("/v1/transactions").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", uniqueId("key"))
                        .content("{\"description\":\"one entry\",\"entries\":["
                                + "{\"accountId\":\"" + a + "\",\"direction\":\"CREDIT\",\"amount\":10.00}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("MIN_TWO_ENTRIES"));
    }

    @Test
    void refusesAnEntryPostingToAnUnknownAccount() throws Exception {
        String a = openAccount("EUR", null);

        mockMvc.perform(post("/v1/transactions").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", uniqueId("key"))
                        .content("{\"description\":\"unknown\",\"entries\":["
                                + "{\"accountId\":\"" + a + "\",\"direction\":\"DEBIT\",\"amount\":10.00},"
                                + "{\"accountId\":\"" + uniqueId("missing") + "\",\"direction\":\"CREDIT\",\"amount\":10.00}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("UNKNOWN_ACCOUNT"));
    }

    @Test
    void cannotReverseAReversal() throws Exception {
        String a = openAccount("EUR", "10.00");
        String b = openAccount("EUR", "10.00");

        String response = recordTransaction("{\"description\":\"transfer\",\"entries\":["
                + "{\"accountId\":\"" + a + "\",\"direction\":\"DEBIT\",\"amount\":5.00},"
                + "{\"accountId\":\"" + b + "\",\"direction\":\"CREDIT\",\"amount\":5.00}]}");
        String transactionId = com.jayway.jsonpath.JsonPath.read(response, "$.id");

        String reversalResponse = mockMvc.perform(post("/v1/transactions/{id}/reversals", transactionId)
                        .header("Idempotency-Key", uniqueId("key")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reversalId = com.jayway.jsonpath.JsonPath.read(reversalResponse, "$.id");

        mockMvc.perform(post("/v1/transactions/{id}/reversals", reversalId)
                        .header("Idempotency-Key", uniqueId("key")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CANNOT_REVERSE_A_REVERSAL"));
    }
}

