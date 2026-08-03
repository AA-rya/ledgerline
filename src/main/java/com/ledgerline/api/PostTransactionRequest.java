package com.ledgerline.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PostTransactionRequest(
        @NotBlank String idempotencyKey,
        String description,
        @Size(min = 2, message = "a transaction needs at least two entries (one debit, one credit)")
        @Valid List<PostEntryRequest> entries
) {}
