# ledger-api

A small double-entry ledger: accounts, balanced transactions, reversals, and the two ways a
transaction gets recorded - a REST call, or a payment-settled message from the payment system.
Every recorded transaction is announced to other services over Kafka.

## Running it

You need Docker, Git and a JDK on the host - nothing else.

```bash
./run.sh
```

This builds the application image, starts MySQL and Kafka, waits for them to be healthy, runs the
full test suite (unit + integration, against the real database and broker via Testcontainers),
then starts the application. When it's done:

- API: http://localhost:8080
- Actuator health: http://localhost:8080/actuator/health
- MySQL: `localhost:3306` (`root` / `passw0rd`, db `ledger_api`)
- Kafka: `localhost:9094` from the host, `kafka:9092` inside the compose network
- Optional dev tools (Kafka UI on `:8081`): `docker compose --profile tools up -d`

To just run the tests (also via Testcontainers, no running stack needed): `./gradlew test`.

## Sample requests

Every mutating request (`POST`) requires an `Idempotency-Key` header - see
[Idempotency](#idempotency) below for why.

**Open an account:**

```bash
curl -X POST http://localhost:8080/v1/accounts \
  -H "Content-Type: application/json" -H "Idempotency-Key: open-merchant-42" \
  -d '{"id":"merchant-42","currency":"EUR"}'
```

**Read it back:**

```bash
curl http://localhost:8080/v1/accounts/merchant-42
```

**Record a transaction** (example 2 from the spec: Merchant 42 is paid out 98.50 EUR, with a fee
of 1.50):

```bash
curl -X POST http://localhost:8080/v1/transactions \
  -H "Content-Type: application/json" -H "Idempotency-Key: payout-merchant-42-001" \
  -d '{
    "description": "Payout",
    "entries": [
      {"accountId": "merchant-42", "direction": "DEBIT",  "amount": 100.00},
      {"accountId": "payouts-eur", "direction": "CREDIT", "amount": 98.50},
      {"accountId": "fees-eur",    "direction": "CREDIT", "amount": 1.50}
    ]
  }'
```

**Reverse it:**

```bash
curl -X POST http://localhost:8080/v1/transactions/{id}/reversals \
  -H "Idempotency-Key: reverse-payout-merchant-42-001"
```

**An account's entries, newest first, paged:**

```bash
curl "http://localhost:8080/v1/accounts/merchant-42/entries?page=0&size=20"
```

**Send a sample payment-settled message** (as the payment system would):

```bash
./scripts/send-sample-payment.sh merchant-42 125.50 EUR
```

This opens the account if it doesn't exist yet, then publishes a JSON message to the
`payment.settled` Kafka topic using `kafka-console-producer.sh` inside the running Kafka
container. Check the result with `curl http://localhost:8080/v1/accounts/merchant-42`.

## Design decisions

### Domain model

`Account` (id, currency, overdraft limit, balance), `Transaction` (id, description, recorded-at,
optional `reversalOfId`) and `Entry` (account, direction, amount, balance-after) map directly onto
the spec, as plain JPA entities with a thin service on top - no repository/use-case/port
abstraction layers, since the whole domain fits comfortably in one service
(`LedgerService`) that is the single place money moves, whether the trigger is a REST call or a
Kafka message. Money is `BigDecimal`, column type `DECIMAL(19,4)` - never a float, so
`0.10 + 0.20` is always exactly `0.30`, and MySQL does the same exact decimal arithmetic Java does.

Currencies are a fixed enum (`EUR`, `USD`, `JPY`) carrying their ISO 4217 decimal places.
`CurrencyCode.fromIsoCode` first checks the code against `java.util.Currency` (every real ISO 4217
code) before checking it against the ledger's supported set, so "EURO"/"ABC" and "GBP" get
different, specific error codes, as the spec's refusal table asks for.

### Recording a transaction: one method, one lock order, checks before writes

`LedgerService.record(...)` is the only place a transaction is created. It:

1. Validates shape (≥2 entries, positive amounts) with no database access at all.
2. Locks every **distinct** account touched, in ascending id order
   (`AccountRepository.lockById`, `PESSIMISTIC_WRITE`), *before* reading any balance. Two
   transactions that touch the same accounts in opposite order (A→B and B→A) therefore always
   acquire their row locks in the same global order, so they can never deadlock - one simply waits
   for the other's transaction to commit.
3. Validates existence, currency match, decimal places, balance, and overdraft limits - entirely
   in memory, against the now-locked, now-current balances.
4. Only if every check passes: builds the `Transaction` and its `Entry` rows (each carrying the
   balance immediately after it) and persists them, applying every delta to the already-locked
   `Account` rows in the same transaction.

Every check runs before any write, so a refused request leaves nothing behind - verified directly
(`refusesATransactionThatWouldExceedAnOverdraftLimit` asserts both balances are still zero
afterwards). A reversal is just `record()` called with every entry flipped and `reversalOfId` set;
it is refused by the same rules (including overdraft), and a second reversal is refused outright
(`reversal_of_id` is unique-constrained, and checked up front too, so the error is a clean
`ALREADY_REVERSED` rather than a generic constraint violation in the common case - the constraint
is the real guarantee under a race, the up-front check is just a nicer error message the rest of
the time).

### REST API

Versioned under `/v1`. Resources: `/v1/accounts`, `/v1/accounts/{id}/entries`,
`/v1/transactions`, `/v1/transactions/{id}/reversals`. Errors are RFC 7807 `application/problem+json`
with a stable machine-readable `errorCode` (e.g. `OVERDRAFT_LIMIT_EXCEEDED`,
`CURRENCY_MISMATCH`, `UNKNOWN_ACCOUNT`) and, where it's about specific entries, an `errors` array
pointing at which one. A breaking change to a request/response shape would go under `/v2`, leaving
`/v1` clients unaffected.

### Idempotency

Every mutating endpoint requires an `Idempotency-Key` header. Natural keys the domain already has
(an account id, a transaction's `reversalOfId`) can tell you *"this already happened"*, but can't
give a retry back the *exact* original response - success **or** business error, verbatim - and
can't detect "the same key was just reused for a genuinely different request"; `POST
/v1/transactions` has no natural key at all, since transaction ids are server-generated. So instead
of special-casing each endpoint's natural key and still needing a response cache bolted onto it,
there is one mechanism, used uniformly by all three mutating endpoints.

Mechanism: the key, scoped to the endpoint (and to the transaction id, for reversals), is claimed
by inserting a row into `idempotency_record` in its own (`REQUIRES_NEW`) transaction, hashing the
request body, *before* any business logic runs. Two concurrent copies of the same request -
even landing on different instances - race on that single database unique-key insert rather than
on business state. The loser is told the request is already in progress (if it got there first) or
is replayed the winner's cached response (success or business error, verbatim, same status code)
once the winner finishes. A key reused with a different body is rejected
(`IDEMPOTENCY_KEY_REUSED`): the ledger never mistakes a different request for a retry.

### Redelivery and the payment-settled consumer

`PaymentSettledConsumer` consumes `payment.settled` and calls
`LedgerService.recordPaymentSettled`, which is idempotent on **`paymentId`**, deliberately not
`eventId` - "redelivered under a brand-new event id" is explicitly a redelivery, not a new
payment. `processed_payment` has `payment_id` as its primary key; the row is inserted in the very
same transaction as the ledger entries, so if two deliveries of the same payment race each other
(e.g. two consumer instances, or a crash-and-redeliver), only one commits and the other's
unique-constraint violation is treated as "already processed" - not an error.

A message that can't be recorded for a business reason (malformed JSON, missing fields, an unknown
account, the wrong currency) is published to `payment.settled.dlq` together with why, logged, and
the offset is committed - so it never blocks the messages behind it and is never lost silently. A
message that fails for an infrastructure reason (the database briefly unavailable) is left
unacknowledged and retried by Kafka's normal consumer redelivery.

### Announcing recorded transactions

Every recorded transaction (reversals included) is written to `outbox_event` in the **same**
database transaction that records it (the transactional outbox pattern), carrying the full
transaction and its entries as JSON. A separate `OutboxPublisher`, on a fixed schedule, locks a
batch of unpublished rows (`FOR UPDATE SKIP LOCKED` - so several ledger instances can poll
concurrently without fighting over rows), publishes each to `ledger.transaction.recorded` keyed by
transaction id (so Kafka preserves per-transaction ordering), and marks it published.

What a consumer can rely on:

- **It will not miss a transaction.** The outbox row is committed atomically with the ledger
  entries; if the service crashes right after recording, the row is already there, and the next
  poll (on any instance) picks it up.
- **It may see one twice.** The Kafka send and the "mark published" commit are two separate
  systems; a crash between them (send acknowledged, commit not yet made) means the row is
  retried on the next poll and sent again. Consumers should dedupe by `transactionId` if that
  matters to them.
- **Order:** per-transaction ordering is preserved (same partition key), but no ordering is
  promised *across* different transactions' events relative to each other beyond "eventually, in
  roughly the order they were recorded" (the poller processes oldest-unpublished-first).
- **A crash right after recording:** the transaction and its outbox row are already durably
  committed together - the event is announced on the next poll, by this instance restarting or by
  any other.

### Concurrency and integrity

Proven directly, against a real MySQL database, in `ConcurrencyIntegrationTest`: 16 threads (8 each
direction) do 25 transfers apiece between the same two accounts, A→B and B→A simultaneously - no
deadlock, no lost update (balances return to exactly their starting point), and the sum of all EUR
account balances is exactly zero throughout. `OVERDRAFT_LIMIT_EXCEEDED` and
`CURRENCY_MISMATCH` tests separately assert a refused transaction leaves balances untouched.

## Assumptions and reasonable calls made along the way

- **Java/Spring Boot version**: the brief asks for Java 17; this project targets Java 25 (Gradle
  toolchain) and Spring Boot 4.1.2, which itself requires a baseline newer than Java 17. This was
  an early, deliberate choice to build against the current Spring Boot major version rather than
  an older LTS combination - everything still runs from nothing but Docker, Git and a JDK, exactly
  as required, since the Docker build stage pulls its own JDK and the host JDK is only needed by
  the Gradle wrapper.
- **Settlement accounts** `settlement-eur`, `settlement-usd`, `settlement-jpy` are seeded by the
  Liquibase changelog with no overdraft limit, at zero balance, as the spec requires.
- **Idempotency-Key is required**, not optional, on every mutating endpoint - a client that wants
  the "retry is always safe" guarantee has to supply one; there's no safe default for "no key
  given" beyond "don't dedupe", which isn't the guarantee asked for.
- **The amount column is `DECIMAL(19,4)`**: enough room for any of the three supported currencies'
  decimal places, with headroom; the *allowed* decimal places are enforced in application code per
  currency (`AMOUNT_EXCEEDS_CURRENCY_DECIMALS`), not by varying the column's scale per row.
- **Entry order** (`entry_order`) is assigned explicitly in `Transaction.addEntry`, not left to
  JPA's `@OrderColumn`: that annotation needs the owning collection to be a Hibernate-managed
  `PersistentList` to populate the column during a cascade-persist, which a brand-new parent's
  plain `ArrayList` is not - discovered the hard way, via `ConcurrencyIntegrationTest`'s sibling,
  the ordinary "record a transaction" test, which committed-but-failed on `entry_order NOT NULL`.
- **`Transaction`, `OutboxEvent`, `IdempotencyRecord` and `ProcessedPayment`** all implement
  Spring Data's `Persistable`: their ids are assigned by the application (UUIDs or caller-supplied
  strings), not the database, and none has a `@Version` field - without `Persistable`, Spring Data
  JPA's default "is this new?" check (`id == null`) says no, so `save()` calls `merge()` instead of
  `persist()`. For `Transaction` that broke entry cascading outright (merge doesn't cascade to a
  `PERSIST`-only association); for `IdempotencyRecord` and `ProcessedPayment` it was worse and
  silent - a "duplicate" claim would `merge()` (quietly **update** the existing row) instead of
  `persist()` (insert, and throw on a unique-constraint violation), which is exactly the signal the
  claim-row-first idempotency and payment-dedupe mechanisms rely on to detect a race. Also found
  and fixed via the test suite, not by inspection.
- **The payment-settled message shape** is `{eventId, paymentId, accountId, amount, currency}` -
  not specified further by the brief, so a reasonably minimal shape was chosen.
- **Reversal response/semantics**: a reversal's own `Idempotency-Key` is scoped per-transaction
  (`reverse-transaction:{id}`), so retrying "reverse transaction X" is safe, but two different
  idempotency keys both legitimately trying to reverse X still only let one through (the
  `reversal_of_id` unique constraint is the real guarantee; the up-front `existsByReversalOfId`
  check is just for a clean error in the non-race case).

## Improvements

What I'd change before production:

- **Outbox publishing is at-least-once with no consumer-side idempotency helper.** I'd either move
  to a transactional/exactly-once Kafka producer, or ship a small library/contract that documents
  (and maybe helps implement) dedup-by-`transactionId` for consumers that need it.
- **No API-first contract file.** I'd write the OpenAPI spec first and generate the DTOs/validate
  against it in CI, rather than letting the code and a prose README drift apart over time.
- **Idempotency record and outbox event tables grow forever.** Both need a retention/cleanup job
  (e.g. delete idempotency records after some days past completion, archive published outbox rows)
  that doesn't exist yet.
- **No authentication/authorization** on the API at all - fine for this exercise, not for a real
  payments platform.
- **Pagination is offset-based** (`page`/`size`); at real scale for a very active account, a
  keyset/cursor-based scheme would be better for tail latency.
- **The outbox poller's `fixedDelayString` is static** per instance; I'd want it configurable per
  environment without a redeploy, and I'd want a metric on outbox lag (oldest unpublished row's
  age), not just logs.

What I skipped, and why:

- A full OpenAPI/Swagger document - the brief allows "if something isn't specified, make a
  reasonable call and move on"; given the time available I prioritized the guarantees in section 6
  (concurrency, idempotency, redelivery, integrity) and the tests that prove them over API
  tooling.
- A separate "balance as at a past instant" cache/snapshot table - `GET .../entries?asOf=...`
  answers this today by filtering entries, which is correct but does a full scan of that account's
  history for very old `asOf` values on a very active account; a snapshot table would fix that, but
  wasn't needed to pass the functional requirement.

