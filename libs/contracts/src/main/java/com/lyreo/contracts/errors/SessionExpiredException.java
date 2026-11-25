package com.lyreo.contracts.errors;

import java.time.Instant;

/**
 * Thrown when a learner attempts to modify or submit an expired session.
 */
public class SessionExpiredException extends StateConflictException {
    private final Instant expiredAt;

    public SessionExpiredException(String message) {
        this(message, null);
    }

    public SessionExpiredException(String message, Instant expiredAt) {
        super(message);
        this.expiredAt = expiredAt;
    }

    public Instant expiredAt() {
        return expiredAt;
    }
}
