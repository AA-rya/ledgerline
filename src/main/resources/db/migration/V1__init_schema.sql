-- Accounts: the chart-of-accounts side of the ledger. `balance` is a
-- materialized, denormalized value kept in sync transactionally with
-- every posted entry (see ADR 0002) -- entries remain the source of
-- truth; balance is an optimization for O(1) reads.
CREATE TABLE accounts (
    id              UUID PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    account_type    VARCHAR(32) NOT NULL CHECK (account_type IN
                        ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
    currency        VARCHAR(3) NOT NULL,
    balance_minor   BIGINT NOT NULL DEFAULT 0,
    version         BIGINT NOT NULL DEFAULT 0,   -- optimistic locking
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Transactions: one logical business event (a payment, a transfer, an
-- adjustment). Balance invariant (sum of debit entries == sum of credit
-- entries) is enforced in the service layer before this row is created,
-- never at read time.
CREATE TABLE ledger_transactions (
    id                  UUID PRIMARY KEY,
    idempotency_key     VARCHAR(255) NOT NULL UNIQUE,
    description         VARCHAR(1024),
    status              VARCHAR(16) NOT NULL DEFAULT 'POSTED'
                            CHECK (status IN ('POSTED','REVERSED')),
    reversed_by_tx_id   UUID REFERENCES ledger_transactions(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Entries: the append-only, immutable double-entry log. Never updated or
-- deleted -- corrections happen via a new reversing transaction, never
-- by mutating history.
CREATE TABLE ledger_entries (
    id              UUID PRIMARY KEY,
    transaction_id  UUID NOT NULL REFERENCES ledger_transactions(id),
    account_id      UUID NOT NULL REFERENCES accounts(id),
    direction       VARCHAR(6) NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount_minor    BIGINT NOT NULL CHECK (amount_minor > 0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_entries_account_id ON ledger_entries(account_id);
CREATE INDEX idx_ledger_entries_transaction_id ON ledger_entries(transaction_id);

-- Idempotency ledger: tracks in-flight and completed requests by client-
-- supplied key so retried POSTs (network timeout, client retry logic)
-- never double-post. `request_hash` catches the "same key, different
-- body" misuse case rather than silently returning the wrong result.
CREATE TABLE idempotency_records (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    request_hash    VARCHAR(64) NOT NULL,
    status          VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','COMPLETED','FAILED')),
    transaction_id  UUID REFERENCES ledger_transactions(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);
