package com.lyreo.platform.jobs.application;

/**
 * Result of an atomic cancellation request on a background job.
 */
public enum CancellationResult {
    ACCEPTED,
    NOT_FOUND,
    NOT_CANCELLABLE
}
