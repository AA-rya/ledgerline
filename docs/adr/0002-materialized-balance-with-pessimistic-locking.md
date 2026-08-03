# ADR 0002: Materialized account balance, updated under pessimistic lock

## Status
Accepted

## Context
Reading an account's balance needs to be fast (a single row read), but
the source of truth for "what happened" is the append-only entry log.
Two options:
1. Compute balance on read: `SELECT SUM(...) FROM ledger_entries WHERE
   account_id = ?`, correct by construction but O(n) in entry count
   and gets slower as an account accumulates history.
2. Materialize `balance_minor` on the `Account` row, updated
   transactionally every time an entry posts against it.

## Decision
Materialize the balance (option 2), guarded by:
- `SELECT ... FOR UPDATE` (`AccountRepository.findWithLockById`) when
  posting, so two concurrent transactions touching the same account
  serialize instead of racing on a read-modify-write.
- `@Version` optimistic locking as defense in depth underneath the
  pessimistic lock.
- Locks acquired in account-ID sorted order (not request order) across
  every code path, specifically to prevent lock-order-inversion
  deadlocks between concurrent transactions that reference the same
  two accounts in opposite orders.

## Consequences
- O(1) balance reads regardless of account history length.
- Requires care everywhere balance is mutated — there is exactly one
  place this happens (`LedgerService.doPost`), which is deliberate:
  centralizing the only balance-mutating code path is what makes the
  locking-order argument above actually hold.
- Tradeoff not fully closed: this repo does not include a periodic
  reconciliation job that recomputes `balance_minor` from
  `SUM(ledger_entries)` and alerts on drift. In a real production
  system this is the safety net that catches the materialized balance
  ever silently diverging from the entry log (a bug, a bypassed write
  path, a bad migration) — noted here as the natural next addition
  rather than assumed unnecessary.
