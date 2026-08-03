package com.ledgerline.api;

import com.ledgerline.domain.Account;
import com.ledgerline.domain.AccountType;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String name,
        AccountType accountType,
        String currency,
        long balanceMinor,
        Instant createdAt
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getName(),
                account.getAccountType(),
                account.getCurrency(),
                account.getBalanceMinor(),
                account.getCreatedAt()
        );
    }
}
