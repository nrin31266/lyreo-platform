package com.lyreo.contracts.errors;

/**
 * Thrown when an Idempotency-Key is reused with a different request payload.
 */
public class IdempotencyConflictException extends StateConflictException {
    private final String idempotencyKey;

    public IdempotencyConflictException(String message) {
        this(message, null);
    }

    public IdempotencyConflictException(String message, String idempotencyKey) {
        super(message);
        this.idempotencyKey = idempotencyKey;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }
}
