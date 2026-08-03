package com.ledgerline.config;

import com.ledgerline.domain.AccountType;
import com.ledgerline.domain.EntryDirection;

/**
 * Standard double-entry "normal balance side" convention: for
 * ASSET/EXPENSE accounts a debit increases the balance; for
 * LIABILITY/EQUITY/REVENUE accounts a credit increases the balance.
 * Centralized here (rather than inlined in the service) because it's
 * the single most important business rule in the whole system and
 * needs exactly one place a reviewer can go to verify it.
 */
public final class BalanceConvention {

    private BalanceConvention() {}

    public static long signedDelta(AccountType type, EntryDirection direction, long amountMinor) {
        boolean debitIncreases = type == AccountType.ASSET || type == AccountType.EXPENSE;
        boolean thisEntryIncreases = (direction == EntryDirection.DEBIT) == debitIncreases;
        return thisEntryIncreases ? amountMinor : -amountMinor;
    }
}
