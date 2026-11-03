package com.ledgerapi.messaging;

import com.ledgerapi.config.LedgerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Publishes recorded transactions to Kafka from the transactional outbox.
 *
 * <p>Each poll locks a batch of unpublished rows ({@code FOR UPDATE SKIP LOCKED}, so several
 * ledger instances can poll concurrently without fighting over the same rows), publishes each one
 * synchronously (keyed by transaction id, so Kafka preserves per-transaction ordering across
 * partitions), and marks it published. A row that fails to publish is simply left unpublished -
 * it is retried on the next poll - without rolling back any other row in the same batch that
 * already succeeded.
 *
 * <p>Because the Kafka send and the database commit that marks the row published are two
 * separate systems, a crash between them means the same event can be published twice (if the
 * crash happens after Kafka acknowledged the send but before the commit). It can never be lost
 * silently, and it is never published before the transaction that produced it has committed.
 * Consumers should treat delivery as at-least-once and dedupe by the transaction id in the
 * payload if seeing it twice would matter to them.
 */
@Slf4j
@Component
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final LedgerProperties properties;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                            KafkaTemplate<String, String> kafkaTemplate,
                            LedgerProperties properties) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.poll-delay-ms:500}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxEventRepository.lockNextBatch(properties.outbox().batchSize());
        for (OutboxEvent event : batch) {
            try {
                publish(event);
            } catch (Exception e) {
                log.error("Failed to publish outbox event {} ({}); it will be retried on the next poll",
                        event.getId(), event.getEventType(), e);
            }
        }
    }

    private void publish(OutboxEvent event) throws Exception {
        String topic = topicFor(event.getEventType());
        kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload()).get(10, TimeUnit.SECONDS);
        event.markPublished();
        log.info("Published outbox event {} ({}) to {} requestId={}",
                event.getId(), event.getEventType(), topic, event.getRequestId());
    }

    private String topicFor(String eventType) {
        // Only one event type is produced today; this is the seam for adding more.
        return properties.topics().transactionRecorded();
    }
}



