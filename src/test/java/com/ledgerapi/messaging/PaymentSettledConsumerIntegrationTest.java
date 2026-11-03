package com.ledgerapi.messaging;

import com.ledgerapi.AbstractIntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the payment-settled consumer against a real Kafka broker: a settled payment becomes
 * a transaction (example 1 from the spec), redelivery under a brand-new event id is recorded at
 * most once, and a message that can't be recorded is routed to the dead-letter topic instead of
 * blocking the messages behind it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentSettledConsumerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    private static String uniqueId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private void openAccount(String id, String currency) throws Exception {
        mockMvc.perform(post("/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", uniqueId("key"))
                        .content("{\"id\":\"" + id + "\",\"currency\":\"" + currency + "\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void aSettledPaymentDebitsSettlementAndCreditsTheMerchant() throws Exception {
        String merchant = uniqueId("merchant");
        openAccount(merchant, "EUR");

        String payload = "{\"eventId\":\"" + uniqueId("event") + "\",\"paymentId\":\"" + uniqueId("payment")
                + "\",\"accountId\":\"" + merchant + "\",\"amount\":125.50,\"currency\":\"EUR\"}";
        kafkaTemplate.send("payment.settled", merchant, payload).get(10, TimeUnit.SECONDS);

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mockMvc.perform(get("/v1/accounts/{id}", merchant))
                        .andExpect(jsonPath("$.balance").value(125.50)));
    }

    @Test
    void redeliveryUnderABrandNewEventIdIsRecordedOnlyOnce() throws Exception {
        String merchant = uniqueId("merchant");
        openAccount(merchant, "EUR");
        String paymentId = uniqueId("payment");

        String first = "{\"eventId\":\"" + uniqueId("event") + "\",\"paymentId\":\"" + paymentId
                + "\",\"accountId\":\"" + merchant + "\",\"amount\":50.00,\"currency\":\"EUR\"}";
        kafkaTemplate.send("payment.settled", merchant, first).get(10, TimeUnit.SECONDS);

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mockMvc.perform(get("/v1/accounts/{id}", merchant))
                        .andExpect(jsonPath("$.balance").value(50.00)));

        // Same payment, redelivered under a brand-new event id.
        String redelivered = "{\"eventId\":\"" + uniqueId("event") + "\",\"paymentId\":\"" + paymentId
                + "\",\"accountId\":\"" + merchant + "\",\"amount\":50.00,\"currency\":\"EUR\"}";
        kafkaTemplate.send("payment.settled", merchant, redelivered).get(10, TimeUnit.SECONDS);

        // Give the (incorrect, double-processing) outcome a chance to happen, then assert it didn't.
        Thread.sleep(2000);
        mockMvc.perform(get("/v1/accounts/{id}", merchant))
                .andExpect(jsonPath("$.balance").value(50.00));
    }

    @Test
    void aMessageThatCannotBeRecordedIsRoutedToTheDeadLetterTopicAndDoesNotBlockLaterMessages() throws Exception {
        String merchant = uniqueId("merchant");
        openAccount(merchant, "EUR");

        // Unknown account: can't be recorded.
        String bad = "{\"eventId\":\"" + uniqueId("event") + "\",\"paymentId\":\"" + uniqueId("payment")
                + "\",\"accountId\":\"" + uniqueId("no-such-account") + "\",\"amount\":10.00,\"currency\":\"EUR\"}";
        kafkaTemplate.send("payment.settled", "k1", bad).get(10, TimeUnit.SECONDS);

        // A good message sent right behind it must still be processed.
        String good = "{\"eventId\":\"" + uniqueId("event") + "\",\"paymentId\":\"" + uniqueId("payment")
                + "\",\"accountId\":\"" + merchant + "\",\"amount\":10.00,\"currency\":\"EUR\"}";
        kafkaTemplate.send("payment.settled", "k2", good).get(10, TimeUnit.SECONDS);

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mockMvc.perform(get("/v1/accounts/{id}", merchant))
                        .andExpect(jsonPath("$.balance").value(10.00)));
    }
}


