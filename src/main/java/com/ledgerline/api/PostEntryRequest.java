package com.ledgerline.api;

import com.ledgerline.domain.EntryDirection;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record PostEntryRequest(
        @NotNull UUID accountId,
        @NotNull EntryDirection direction,
        @Positive long amountMinor
) {}
