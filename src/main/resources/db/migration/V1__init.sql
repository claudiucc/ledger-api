CREATE TABLE account (
    id               VARCHAR(64)     NOT NULL PRIMARY KEY,
    currency         VARCHAR(3)      NOT NULL,
    overdraft_limit  DECIMAL(19,4)   NULL,
    balance          DECIMAL(19,4)   NOT NULL DEFAULT 0,
    version          BIGINT          NOT NULL DEFAULT 0,
    created_at       DATETIME(6)     NOT NULL
) ENGINE = InnoDB;

CREATE TABLE ledger_transaction (
    id               VARCHAR(36)     NOT NULL PRIMARY KEY,
    description      VARCHAR(500)    NOT NULL,
    recorded_at      DATETIME(6)     NOT NULL,
    reversal_of_id   VARCHAR(36)     NULL,
    request_id       VARCHAR(64)     NULL,
    CONSTRAINT fk_tx_reversal_of FOREIGN KEY (reversal_of_id) REFERENCES ledger_transaction (id),
    CONSTRAINT uq_tx_reversal_of UNIQUE (reversal_of_id)
) ENGINE = InnoDB;

CREATE TABLE ledger_entry (
    id               VARCHAR(36)     NOT NULL PRIMARY KEY,
    transaction_id   VARCHAR(36)     NOT NULL,
    account_id       VARCHAR(64)     NOT NULL,
    direction        VARCHAR(6)      NOT NULL,
    amount           DECIMAL(19,4)   NOT NULL,
    balance_after    DECIMAL(19,4)   NOT NULL,
    entry_order      INT             NOT NULL,
    CONSTRAINT fk_entry_tx FOREIGN KEY (transaction_id) REFERENCES ledger_transaction (id),
    CONSTRAINT fk_entry_account FOREIGN KEY (account_id) REFERENCES account (id)
) ENGINE = InnoDB;

CREATE INDEX idx_entry_account_recorded ON ledger_entry (account_id, transaction_id);

CREATE TABLE idempotency_record (
    id               VARCHAR(160)    NOT NULL PRIMARY KEY,
    request_hash     VARCHAR(64)     NOT NULL,
    status           VARCHAR(16)     NOT NULL,
    response_status  INT             NULL,
    response_body    LONGTEXT        NULL,
    created_at       DATETIME(6)     NOT NULL,
    updated_at       DATETIME(6)     NOT NULL
) ENGINE = InnoDB;

CREATE TABLE processed_payment (
    payment_id       VARCHAR(128)    NOT NULL PRIMARY KEY,
    event_id         VARCHAR(128)    NOT NULL,
    transaction_id   VARCHAR(36)     NOT NULL,
    processed_at     DATETIME(6)     NOT NULL
) ENGINE = InnoDB;

CREATE TABLE outbox_event (
    id               VARCHAR(36)     NOT NULL PRIMARY KEY,
    aggregate_type   VARCHAR(32)     NOT NULL,
    aggregate_id     VARCHAR(36)     NOT NULL,
    event_type       VARCHAR(64)     NOT NULL,
    payload          LONGTEXT        NOT NULL,
    request_id       VARCHAR(64)     NULL,
    created_at       DATETIME(6)     NOT NULL,
    published_at     DATETIME(6)     NULL
) ENGINE = InnoDB;

CREATE INDEX idx_outbox_unpublished ON outbox_event (published_at, created_at);

-- Ledger's own settlement accounts: pre-opened, no overdraft limit.
INSERT INTO account (id, currency, overdraft_limit, balance, version, created_at)
VALUES
    ('settlement-eur', 'EUR', NULL, 0, 0, UTC_TIMESTAMP(6)),
    ('settlement-usd', 'USD', NULL, 0, 0, UTC_TIMESTAMP(6)),
    ('settlement-jpy', 'JPY', NULL, 0, 0, UTC_TIMESTAMP(6));

