# ADR 0005: DB-backed idempotency reservation, Redis as an optional cache only

## Status
Accepted

## Context
Idempotent request handling needs a single source of truth for "has
this key been used, and with what result." Redis is fast and a common
choice for this via `SETNX`, but using it as the *only* mechanism means
correctness depends on Redis persistence/availability — a Redis
restart with no persistence configured could forget an idempotency key
was ever used, and a client retry after that would double-post.

## Decision
Postgres (`idempotency_records`, with a unique constraint on the key)
is the source of truth, enforced via the `IdempotencyKeyReservationService`
REQUIRES_NEW-transaction reservation pattern (see that class's Javadoc
for why a plain `save()` inside the main transaction isn't sufficient).
Redis is provisioned (see `RedisConfig`) but not wired into the hot
path in this version — it's there for a v2 optimization: caching
`idempotency_key → transaction_id` for very-high-QPS replay scenarios,
as a read-through cache in front of the Postgres check, never as a
replacement for it.

## Consequences
- Correctness never depends on Redis being up or durable: worst case
  without the cache is an extra indexed Postgres lookup per request,
  not a double-post.
- Redis's presence in the stack (per the project's tech-stack target)
  is honest about being provisioned-but-not-yet-load-bearing here,
  rather than forcing it into the correctness-critical path just to
  justify including it.
