package com.ledgerline.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A single debit or credit line within a {@link LedgerTransaction}.
 * Append-only: never updated or deleted after creation.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false)
    private LedgerTransaction transaction;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EntryDirection direction;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
        // JPA
    }

    public LedgerEntry(UUID id, LedgerTransaction transaction, UUID accountId,
                        EntryDirection direction, long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive, got " + amountMinor);
        }
        this.id = id;
        this.transaction = transaction;
        this.accountId = accountId;
        this.direction = direction;
        this.amountMinor = amountMinor;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public LedgerTransaction getTransaction() { return transaction; }
    public UUID getAccountId() { return accountId; }
    public EntryDirection getDirection() { return direction; }
    public long getAmountMinor() { return amountMinor; }
    public Instant getCreatedAt() { return createdAt; }
}
