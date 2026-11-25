package com.lyreo.contracts.errors;

/**
 * Thrown when a write carries a stale optimistic revision or version.
 */
public class StaleRevisionException extends StateConflictException {
    private final Long currentRevision;

    public StaleRevisionException(String message) {
        this(message, null);
    }

    public StaleRevisionException(String message, Long currentRevision) {
        super(message);
        this.currentRevision = currentRevision;
    }

    public Long currentRevision() {
        return currentRevision;
    }
}
