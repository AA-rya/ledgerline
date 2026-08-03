# ADR 0001: Double-entry domain model — Account / LedgerTransaction / LedgerEntry

## Status
Accepted

## Context
The system needs to model money movement such that it's structurally
impossible (not just application-validated) to lose track of where
money came from or went to.

## Decision
Three entities: `Account` (a bucket with a type and a materialized
balance), `LedgerTransaction` (one business event), `LedgerEntry` (one
debit or credit line, always belonging to exactly one transaction and
one account). Entries are append-only. The balance invariant
(sum of debits == sum of credits within a transaction) is enforced in
`LedgerService` before persistence, not via a database constraint,
because it requires aggregating across the request's entry list before
any row exists to constrain.

## Consequences
- History is immutable and auditable: nothing is ever UPDATEd or
  DELETEd from `ledger_entries`. Corrections are new reversing
  transactions.
- The tradeoff: enforcing the balance invariant only in application
  code means a direct SQL insert (bypassing the service layer) could
  violate it. Accepted for v1 since all writes go through
  `LedgerService`; a stricter version could add a Postgres trigger
  that sums entries per transaction_id and rejects unbalanced inserts
  at the database level as defense in depth.
