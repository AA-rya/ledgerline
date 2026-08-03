# Use case: e-commerce order lifecycle (sale -> partial refund)

`examples/ecommerce_order_lifecycle.sh` walks through: post a $120 sale,
post a $30 partial refund as its own transaction, verify Cash reads
exactly $90, then retry the sale with the same idempotency key and
confirm it returns the original transaction without double-posting.

Covers the same paths as `LedgerServiceTest.replayingACompletedIdempotencyKeyReturnsOriginalTransactionWithoutReposting`
and the Testcontainers integration test, demonstrated against a real
running server instead of mocked repositories.
