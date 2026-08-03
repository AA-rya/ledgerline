package com.ledgerline.api;

import com.ledgerline.domain.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateAccountRequest(
        @NotBlank String name,
        @NotNull AccountType accountType,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO 4217 code")
        String currency
) {}
