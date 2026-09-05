package com.lyreo.platform.jobs.application;

import java.util.UUID;

/** Public command facade for enqueue/cancel operations. */
public final class BackgroundJobService {
    private final BackgroundJobRepository repository;
    private final JobStateTransitionService transitions;

    public BackgroundJobService(
        BackgroundJobRepository repository,
        JobStateTransitionService transitions
    ) {
        this.repository = repository;
        this.transitions = transitions;
    }

    public UUID enqueue(
        String type,
        String ownerModule,
        UUID ownerReferenceId,
        int priority,
        int maxAttempts,
        String snapshotJson
    ) {
        return repository.enqueue(
            type, ownerModule, ownerReferenceId, priority, maxAttempts, snapshotJson
        );
    }

    /**
     * Requests cancellation and emits a durable job-state event in the same transaction.
     *
     * @return false if the job does not exist or is already terminal.
     */
    public boolean cancel(UUID jobId) {
        return transitions.requestCancellation(jobId);
    }
}
