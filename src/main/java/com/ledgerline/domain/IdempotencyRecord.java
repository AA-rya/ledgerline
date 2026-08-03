package com.ledgerline.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Tracks in-flight and completed requests by client-supplied
 * idempotency key. `requestHash` guards against the "same key, new
 * body" misuse case: replaying the identical request is safe and
 * returns the original result; reusing a key with a different payload
 * is a client error, not a silent overwrite.
 */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Id
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IdempotencyStatus status;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected IdempotencyRecord() {
        // JPA
    }

    public IdempotencyRecord(String idempotencyKey, String requestHash) {
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.status = IdempotencyStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void markCompleted(UUID transactionId) {
        this.status = IdempotencyStatus.COMPLETED;
        this.transactionId = transactionId;
        this.completedAt = Instant.now();
    }

    public void markFailed() {
        this.status = IdempotencyStatus.FAILED;
        this.completedAt = Instant.now();
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public IdempotencyStatus getStatus() { return status; }
    public UUID getTransactionId() { return transactionId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
