package com.ledgerline.event;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges the domain event LedgerService publishes (inside its DB
 * transaction) to the actual Kafka send (after that transaction has
 * committed). This is the piece that prevents the classic "dual write"
 * bug: publishing to Kafka before/during the DB commit means a DB
 * rollback can leave a phantom event in the stream for a transaction
 * that never actually happened. TransactionPhase.AFTER_COMMIT
 * guarantees this listener only fires once Postgres has durably
 * committed the ledger transaction.
 *
 * Documented limitation (ADR 0004): this is still not exactly-once --
 * if the process crashes after commit but before the Kafka send
 * completes, the event is lost with no automatic retry. A transactional
 * outbox table (write the event to Postgres in the same transaction as
 * the ledger rows, then a separate poller/CDC process relays it to
 * Kafka) would close that gap; it's the natural v2 and is called out
 * explicitly rather than silently assumed away.
 */
@Component
public class LedgerEventCommitListener {

    private final LedgerEventPublisher publisher;

    public LedgerEventCommitListener(LedgerEventPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransactionPosted(TransactionPostedEvent event) {
        publisher.publishPosted(event);
    }
}
