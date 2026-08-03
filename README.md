# Ledgerline

Double-entry ledger and idempotent payments API. Built to demonstrate
the engineering that actually matters in payments infrastructure —
balance invariants, safe concurrent posting, and idempotent retries —
not a CRUD wrapper around a `transactions` table.

> **Verification status:** written and reviewed carefully, but **not
> compiled or executed** — this was authored in a sandbox without
> Maven Central network access or a Docker daemon. Run `mvn clean
> verify` locally (needs Docker for the Testcontainers integration
> tests) before treating this as working code. This is the honest
> status; see `docs/ARCHITECTURE.md` → "Verification status" and
> compare against the [Vane](../vane) project in this same portfolio,
> which *was* fully installed, tested, and benchmarked in-sandbox.

## What this is

A Spring Boot service exposing:
- `POST /api/v1/accounts` — create a ledger account (ASSET, LIABILITY,
  EQUITY, REVENUE, or EXPENSE)
- `POST /api/v1/transactions` — post a balanced double-entry
  transaction, idempotently
- `GET /api/v1/accounts/{id}`, `GET /api/v1/transactions/{id}`

Enforced invariants:
- **Balance**: every transaction's debit entries must sum to exactly
  its credit entries, checked before any row is written.
- **Idempotency**: retried requests (same key, same body) return the
  original result without double-posting; the same key with a
  *different* body is rejected as a conflict rather than silently
  overwriting anything.
- **Concurrency safety**: concurrent postings against the same
  account(s) serialize correctly (pessimistic row locks, acquired in a
  deterministic order to avoid deadlock) rather than losing updates.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the full request
lifecycle and diagram, and [`docs/adr/`](docs/adr/) for the five
design decisions and their explicit tradeoffs (materialized balance vs.
computed, minor-unit integers vs. `BigDecimal`, event publish timing,
idempotency-key reservation, and the domain model itself).

## Running it

```bash
docker compose up --build
```

Starts Postgres, Redis, Kafka (+ Zookeeper), and Ledgerline itself on
`:8080`.

```bash
# create two accounts
curl -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' \
  -d '{"name":"Cash","accountType":"ASSET","currency":"USD"}'
curl -X POST localhost:8080/api/v1/accounts -H 'Content-Type: application/json' \
  -d '{"name":"Sales Revenue","accountType":"REVENUE","currency":"USD"}'

# post a balanced transaction (amounts in cents)
curl -X POST localhost:8080/api/v1/transactions -H 'Content-Type: application/json' -d '{
  "idempotencyKey": "order-123",
  "description": "sale",
  "entries": [
    {"accountId": "<cash-id>", "direction": "DEBIT",  "amountMinor": 5000},
    {"accountId": "<revenue-id>", "direction": "CREDIT", "amountMinor": 5000}
  ]
}'

# retry the exact same request -- returns the same transaction, no double-post
```

## Testing

```bash
mvn test -Dtest='!*IntegrationTest'   # unit tests: pure logic + Mockito, no external services
mvn test -Dtest='*IntegrationTest'    # Testcontainers: real Postgres, real HTTP layer, needs Docker
```

- `BalanceConventionTest` — debit/credit sign convention per account
  type (the single most important business rule in the system).
- `RequestHasherTest` — idempotency-key-reuse-detection hash is stable
  and sensitive to payload changes.
- `LedgerServiceTest` — the core posting engine with repositories
  mocked: rejects unbalanced transactions before touching storage,
  posts and updates both balances correctly, replays a completed
  idempotency key without reposting, rejects key-reuse-with-different-
  body, rejects a concurrent in-flight duplicate, marks a failed
  attempt so it can be safely retried.
- `LedgerServiceIntegrationTest` — full stack against a real
  Testcontainers Postgres: balance updates via real HTTP calls,
  idempotent replay doesn't double the balance, and 20 concurrent
  posts against the same account pair sum correctly (verifies the
  pessimistic locking actually prevents lost updates, not just that
  the code compiles).

## Configuration

Environment variables (see `application.yml`): `DB_HOST`, `DB_PORT`,
`DB_NAME`, `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`,
`KAFKA_BOOTSTRAP_SERVERS`, `SERVER_PORT`.

Observability: `GET /actuator/health`, `GET /actuator/prometheus`
(Micrometer + Prometheus registry).

## Project layout

```
src/main/java/com/ledgerline/
  domain/     Account, LedgerTransaction, LedgerEntry, IdempotencyRecord
  repository/ Spring Data JPA repositories (incl. pessimistic-lock query)
  service/    LedgerService (core engine), AccountService,
              IdempotencyKeyReservationService, RequestHasher
  api/        REST controllers, DTOs, GlobalExceptionHandler
  event/      Kafka event + AFTER_COMMIT publish listener
  config/     Redis, Kafka, BalanceConvention (debit/credit sign rule)
src/main/resources/db/migration/  Flyway schema (V1__init_schema.sql)
docs/                              Architecture doc + 5 ADRs
```

## What's deliberately out of scope (v1)

- Transactional outbox for event publish (ADR 0004) — current publish-
  after-commit avoids phantom events but not lost-on-crash events.
- Reconciliation job to catch materialized-balance drift from the
  entry log (ADR 0002).
- Currency-exponent reference table (ADR 0003) — minor-unit conversion
  currently assumes the caller knows each currency's decimal places.
- Transaction reversal endpoint (the `reversed_by_tx_id` column and
  `LedgerTransaction.markReversed()` exist in the model but there's no
  `POST /api/v1/transactions/{id}/reverse` yet).
