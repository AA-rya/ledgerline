package com.ledgerline.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One logical business event: a set of balanced double-entry postings.
 * Immutable once created -- corrections are made by posting a new
 * reversing transaction (see {@link #reversedByTxId}), never by
 * mutating this row or its entries.
 */
@Entity
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(length = 1024)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(name = "reversed_by_tx_id")
    private UUID reversedByTxId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    private List<LedgerEntry> entries = new ArrayList<>();

    protected LedgerTransaction() {
        // JPA
    }

    public LedgerTransaction(UUID id, String idempotencyKey, String description) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.description = description;
        this.status = TransactionStatus.POSTED;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getDescription() { return description; }
    public TransactionStatus getStatus() { return status; }
    public UUID getReversedByTxId() { return reversedByTxId; }
    public Instant getCreatedAt() { return createdAt; }
    public List<LedgerEntry> getEntries() { return entries; }

    public void markReversed(UUID reversingTxId) {
        this.status = TransactionStatus.REVERSED;
        this.reversedByTxId = reversingTxId;
    }
}
