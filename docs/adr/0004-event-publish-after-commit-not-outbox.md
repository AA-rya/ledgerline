# ADR 0004: Publish Kafka events after commit via TransactionalEventListener, not a transactional outbox

## Status
Accepted (v1 scope, gap documented)

## Context
Publishing "transaction posted" events needs to avoid the classic dual-
write bug: if you publish to Kafka *during* the same code path as the
DB write but before commit, a later rollback leaves a phantom event for
a transaction that never actually happened. Two ways to avoid it:
1. Publish only after the DB transaction has committed
   (`@TransactionalEventListener(phase = AFTER_COMMIT)`), implemented
   here.
2. Transactional outbox: write the event to an `outbox` table in the
   *same* transaction as the ledger rows (so it either commits with
   them or not at all), then a separate poller or CDC process
   (Debezium, etc.) relays outbox rows to Kafka asynchronously.

## Decision
Option 1 for v1.

## Consequences
- Solves the dual-write/phantom-event problem: nothing is ever
  published for a transaction that didn't commit.
- Does **not** solve the opposite gap: if the process crashes after
  the DB commit but before the Kafka send completes (or the send fails
  and is only logged, per `LedgerEventPublisher`), that event is lost
  with no automatic retry. At-least-once delivery is not actually
  guaranteed by this implementation — it's "best-effort, after commit."
- Option 2 (transactional outbox) closes that gap by making publish
  retry-able and crash-safe, at the cost of an extra table, a relay
  process, and a small delivery-latency increase. This is the correct
  v2 for anything where a lost event has real business consequences
  (e.g. triggering downstream settlement) — called out explicitly
  rather than left as an unstated assumption.
