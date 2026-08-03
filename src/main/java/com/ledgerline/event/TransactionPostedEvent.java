package com.ledgerline.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published to Kafka after a transaction is durably committed to
 * Postgres. Consumers (downstream reporting, fraud checks, statement
 * generation, etc.) treat this as at-least-once: the publish happens
 * after commit, not inside it (see ADR 0004), so a crash between commit
 * and publish means a consumer may need to poll/reconcile rather than
 * rely solely on the event stream for correctness-critical state.
 */
public record TransactionPostedEvent(
        UUID transactionId,
        String idempotencyKey,
        Instant postedAt
) {}
