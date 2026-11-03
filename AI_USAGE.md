# AI usage

I used an AI coding assistant (GitHub Copilot, agent mode) throughout this exercise, for:

- **Scaffolding and iterating on the implementation**: the domain model, the ledger service layer
  (`AccountService`, `TransactionService`, `PaymentSettlementService`),
  controllers, exception handling, the Kafka outbox/consumer, idempotency layer, and the
  Testcontainers-based test suite were built and refined with the assistant, in an iterative
  back-and-forth rather than one big generation.
- **Design discussion**: e.g. whether a separate `IdempotencyRecord`/`idempotency_record` table was
  justified given that some endpoints already have a natural key (account id, `reversalOfId`,
  `paymentId`) - the assistant was asked to justify the design rather than just produce code, and
  the answer (natural keys tell you "this happened"; they can't replay the *original response*,
  including errors, or detect "same key, different body") shaped what stayed and what didn't.
- **Debugging real, reproduced failures** - not hypothetical ones. Three genuine bugs were found
  and fixed this way, each confirmed by actually running the test suite against real MySQL and
  Kafka (via Testcontainers), not by code review alone:
  1. `Transaction`, `OutboxEvent`, `IdempotencyRecord` and `ProcessedPayment` all use
     application-assigned ids with no `@Version` field, which makes Spring Data JPA's default
     `isNew()` check say "false" for a brand-new entity, so `save()` silently calls
     `EntityManager.merge()` instead of `persist()`. For `Transaction` this broke cascading the
     persist of its `entries` (merge doesn't cascade to a `PERSIST`-only association) with a
     Hibernate `ObjectRetrievalFailureException`. For `IdempotencyRecord` and `ProcessedPayment` it
     was more dangerous and silent: a "duplicate claim" would `merge()` (quietly update the
     existing row) instead of `persist()` (insert, and throw on the unique-constraint violation the
     whole claim-row-first idempotency/redelivery-dedup mechanism depends on). Fixed by
     implementing `Persistable<String>` with a transient `isNew` flag on all four entities.
  2. `@OrderColumn` on the `Transaction.entries` `mappedBy` association didn't populate the
     `entry_order` column during cascade-persist (it needs the collection to already be a
     Hibernate-tracked `PersistentList`, which a brand-new parent's plain `ArrayList` isn't),
     causing a `entry_order doesn't have a default value` failure at commit. Fixed by assigning
     the order explicitly in `Transaction.addEntry`/`Entry` and switching to a read-only
     `@OrderBy("entryOrder asc")`.
  3. Spring Boot 4's `spring-boot-autoconfigure` no longer bundles Kafka autoconfiguration (it
     moved to a separate `org.springframework.boot:spring-boot-kafka` module); without it,
     `KafkaTemplate`/`@KafkaListener` support silently wasn't autoconfigured and the context failed
     to start. Found by actually running `./gradlew test` and reading the `NoSuchBeanDefinitionException`.
- **Writing this README and this file.**
- **Splitting the service layer**: the original single `LedgerService` (accounts, transaction
  recording/reversal, outbox publishing, and payment-settlement, all in one class) was split, on
  request, into `AccountService`, `TransactionService` and `PaymentSettlementService`, each with
  one responsibility and only the dependencies it needs - re-verified by recompiling and rerunning
  the full test suite (including the cross-service transaction-propagation path in
  `PaymentSettlementService`, which must join `TransactionService`'s transaction rather than start
  its own) afterwards, not assumed correct from the diff alone.

How it was checked: every change was validated by actually compiling (`./gradlew compileJava`/
`compileTestJava`) and running the real test suite (`./gradlew test`, backed by Testcontainers
MySQL and Kafka - not mocks) after each meaningful change, not by reading the generated code and
assuming it was correct. The concurrency test (16 threads transferring between two real,
MySQL-backed accounts in both directions at once) and the Kafka redelivery/dead-letter tests were
run against the real containers before being considered done.

No instruction/prompt files were used beyond the task description given in the conversation; there
is no separate prompt file to commit.

