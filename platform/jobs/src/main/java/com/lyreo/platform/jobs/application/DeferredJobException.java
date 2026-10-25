package com.lyreo.platform.jobs.application;

/** A provider supplied a retry time; waiting is durable rather than sleeping in a worker. */
public final class DeferredJobException extends RuntimeException {
    private final java.time.Instant retryAt;
    public DeferredJobException(String message, java.time.Instant retryAt) {
        super(message); this.retryAt = retryAt;
    }
    public java.time.Instant retryAt() { return retryAt; }
}
