package com.ledgerline.api;

import com.ledgerline.domain.EntryDirection;
import com.ledgerline.domain.LedgerTransaction;
import com.ledgerline.domain.TransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        String description,
        TransactionStatus status,
        List<EntryView> entries,
        Instant createdAt
) {
    public record EntryView(UUID accountId, EntryDirection direction, long amountMinor) {}

    public static TransactionResponse from(LedgerTransaction tx) {
        List<EntryView> views = tx.getEntries().stream()
                .map(e -> new EntryView(e.getAccountId(), e.getDirection(), e.getAmountMinor()))
                .toList();
        return new TransactionResponse(tx.getId(), tx.getDescription(), tx.getStatus(), views, tx.getCreatedAt());
    }
}
