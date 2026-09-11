package com.lyreo.platform.jobs.application;

import com.lyreo.platform.jobs.domain.BackgroundJob;
import java.util.Optional;
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

    /** Reads a job by ID. Returns empty if the job does not exist. */
    public Optional<BackgroundJob> findById(UUID jobId) {
        return repository.findById(jobId);
    }

    /**
     * Requests cancellation and emits a durable job-state event in the same transaction.
     *
     * @return CancellationResult indicating ACCEPTED, NOT_FOUND, or NOT_CANCELLABLE.
     */
    public CancellationResult cancel(UUID jobId) {
        return transitions.requestCancellation(jobId);
    }
}
