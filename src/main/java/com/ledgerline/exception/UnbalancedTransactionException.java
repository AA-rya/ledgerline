package com.ledgerline.exception;

/** Thrown when sum(debit entries) != sum(credit entries) for a posting request. */
public class UnbalancedTransactionException extends RuntimeException {
    public UnbalancedTransactionException(long debitTotal, long creditTotal) {
        super("Transaction is not balanced: debits=" + debitTotal + " credits=" + creditTotal);
    }
}
