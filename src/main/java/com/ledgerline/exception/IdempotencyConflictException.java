package com.ledgerline.exception;

/**
 * Thrown when an idempotency key is reused with a materially different
 * request body, or when a concurrent request with the same key is
 * still in flight. Maps to HTTP 409.
 */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}
