package com.ledgerline.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * A ledger account. `balanceMinor` is a materialized cache of
 * sum(credit entries) - sum(debit entries) or the inverse, depending on
 * {@link AccountType} normal balance side -- see LedgerService for the
 * sign convention. It is updated transactionally alongside every posted
 * entry and protected by {@code @Version} optimistic locking so two
 * concurrent postings against the same account can't silently lose an
 * update (see ADR 0002).
 *
 * Money is stored in minor units (cents) as a long, not
 * {@code BigDecimal} or a float, to avoid rounding/precision ambiguity
 * across currencies with different exponents -- the same convention
 * used by Stripe and most payment processors (ADR 0003).
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private AccountType accountType;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
        // JPA
    }

    public Account(UUID id, String name, AccountType accountType, String currency) {
        this.id = id;
        this.name = name;
        this.accountType = accountType;
        this.currency = currency;
        this.balanceMinor = 0L;
        this.createdAt = Instant.now();
    }

    /**
     * Apply a signed delta to the balance. Positive increases the
     * account's normal-balance-side total; negative decreases it. The
     * caller (LedgerService) is responsible for translating
     * debit/credit direction into the correct sign for this account's
     * type.
     */
    public void applyDelta(long deltaMinor) {
        this.balanceMinor += deltaMinor;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public AccountType getAccountType() { return accountType; }
    public String getCurrency() { return currency; }
    public long getBalanceMinor() { return balanceMinor; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
}
