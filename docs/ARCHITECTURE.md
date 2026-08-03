# Ledgerline Architecture

```
                  ┌────────────────────────────┐
                  │      REST API (Spring)      │
                  │  /api/v1/accounts             │
                  │  /api/v1/transactions          │
                  └───────────┬────────────────┘
                              │
                  ┌───────────▼────────────────┐
                  │        LedgerService          │
                  │  1. validate balance           │
                  │  2. idempotency check/reserve   │
                  │  3. lock accounts (sorted ids)  │
                  │  4. post entries + update       │
                  │     balances                     │
                  │  5. publish domain event         │
                  │     (in-transaction)              │
                  └───────────┬────────────────┘
                              │ commits
                  ┌───────────▼────────────────┐
                  │        PostgreSQL             │
                  │  accounts / ledger_transactions│
                  │  / ledger_entries /             │
                  │  idempotency_records             │
                  └───────────┬────────────────┘
                              │ AFTER_COMMIT
                  ┌───────────▼────────────────┐
                  │  LedgerEventCommitListener    │
                  │        └──▶ Kafka              │
                  │      ledger.transaction.posted │
                  └────────────────────────────┘

  Redis: available for a fast idempotency-replay cache in front of
  Postgres (not wired into the hot path in v1 -- see ADR 0005).
```

## Request lifecycle: `POST /api/v1/transactions`

1. **Balance validation** (`LedgerService.validateBalanced`): sum of
   DEBIT entries must equal sum of CREDIT entries, checked before any
   database write. A malformed request never consumes an idempotency
   key.
2. **Idempotency check**: look up `idempotency_records` by the
   client-supplied key.
   - Key seen before, same request body, `COMPLETED` → return the
     original transaction (safe replay, no double-post).
   - Key seen before, **different** request body → 409 (key reuse is a
     client bug, not silently resolved).
   - Key seen before, still `PENDING` (concurrent in-flight duplicate)
     → 409.
   - Key never seen → reserved in its own transaction (see ADR 0004)
     before proceeding, so a concurrent racer on the same brand-new key
     fails the reservation and falls back to reading the winner's
     result instead of both racing to insert a transaction row.
3. **Account locking**: every account referenced by the request is
   locked with `SELECT ... FOR UPDATE` (`findWithLockById`), in
   **account-id sorted order** — not request order — across every
   caller. This is what prevents a classic distributed-lock deadlock:
   transaction A posting (account 1 → account 2) and transaction B
   posting (account 2 → account 1) concurrently would deadlock if each
   locked in its own request's order; sorting by ID gives every caller
   the same lock order regardless of how the request was written.
4. **Posting**: entries are created, and each account's materialized
   `balance_minor` is updated by a signed delta (see
   `BalanceConvention` — debit/credit sign depends on account type's
   normal balance side).
5. **Idempotency record marked `COMPLETED`** (or `FAILED`, with the
   failure re-raised, if anything above threw).
6. **Event publish deferred to commit**: `LedgerService` publishes a
   Spring `ApplicationEvent` *inside* the transaction, but
   `LedgerEventCommitListener` only forwards it to Kafka
   `@TransactionalEventListener(phase = AFTER_COMMIT)` — so a rollback
   anywhere above means no event is ever sent (see ADR 0004 for the
   residual gap this doesn't close).

## Why minor units (`long`), not `BigDecimal`

Money is stored as an integer count of the currency's minor unit (cents
for USD) rather than a decimal type. This sidesteps an entire class of
floating-point/rounding bugs and matches the convention used by Stripe
and most payment processors — arithmetic on `long` is exact and cheap;
`BigDecimal` arithmetic across currencies with different exponents
(JPY has none, most have 2, some have 3) is a solved problem but an
easy one to get subtly wrong. Presentation-layer formatting (dividing
by the currency's exponent for display) is a client/API-boundary
concern, not a storage concern (see ADR 0003).

## Verification status

This project was authored in a sandbox with no Maven Central network
access and no Docker daemon, so **it has not been compiled or executed
here** — unlike the Vane project (Python), which was fully installed,
tested, and benchmarked in-sandbox. The code has been written and
reviewed carefully, but treat first-build friction (a missing import,
a minor API mismatch against the pinned Spring Boot 3.3.4 / Java 21
versions) as expected, not a sign the design is wrong. Run
`mvn clean verify` locally to compile and execute both the unit and
Testcontainers integration test suites.
