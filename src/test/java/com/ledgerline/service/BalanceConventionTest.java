package com.ledgerline.service;

import com.ledgerline.config.BalanceConvention;
import com.ledgerline.domain.AccountType;
import com.ledgerline.domain.EntryDirection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BalanceConventionTest {

    @Test
    void debitIncreasesAssetAccount() {
        assertEquals(500L, BalanceConvention.signedDelta(AccountType.ASSET, EntryDirection.DEBIT, 500));
    }

    @Test
    void creditDecreasesAssetAccount() {
        assertEquals(-500L, BalanceConvention.signedDelta(AccountType.ASSET, EntryDirection.CREDIT, 500));
    }

    @Test
    void debitIncreasesExpenseAccount() {
        assertEquals(200L, BalanceConvention.signedDelta(AccountType.EXPENSE, EntryDirection.DEBIT, 200));
    }

    @Test
    void creditIncreasesLiabilityAccount() {
        assertEquals(300L, BalanceConvention.signedDelta(AccountType.LIABILITY, EntryDirection.CREDIT, 300));
    }

    @Test
    void debitDecreasesLiabilityAccount() {
        assertEquals(-300L, BalanceConvention.signedDelta(AccountType.LIABILITY, EntryDirection.DEBIT, 300));
    }

    @Test
    void creditIncreasesEquityAccount() {
        assertEquals(1000L, BalanceConvention.signedDelta(AccountType.EQUITY, EntryDirection.CREDIT, 1000));
    }

    @Test
    void creditIncreasesRevenueAccount() {
        assertEquals(750L, BalanceConvention.signedDelta(AccountType.REVENUE, EntryDirection.CREDIT, 750));
    }

    @Test
    void aSimpleCashSaleBalancesAcrossTwoAccountTypes() {
        // Cash (ASSET) debited 100, Revenue (REVENUE) credited 100 --
        // the textbook example this whole convention exists to model.
        long cashDelta = BalanceConvention.signedDelta(AccountType.ASSET, EntryDirection.DEBIT, 100);
        long revenueDelta = BalanceConvention.signedDelta(AccountType.REVENUE, EntryDirection.CREDIT, 100);
        assertEquals(100L, cashDelta);
        assertEquals(100L, revenueDelta);
    }
}
