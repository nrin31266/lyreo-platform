package com.lyreo.platform.jobs.application;

/**
 * Raised when a worker no longer owns the PostgreSQL lease for a job.
 *
 * <p>This is a fencing signal, not a retryable business error. Another worker may have
 * recovered the job after a lease expiry, so the stale worker must stop immediately and
 * must not mark the job failed/succeeded.</p>
 */
public final class JobLeaseLostException extends RuntimeException {
    public JobLeaseLostException(String message) {
        super(message);
    }
}
