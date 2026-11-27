package com.lyreo.platform.jobs.application;

import com.lyreo.platform.jobs.domain.BackgroundJob;
import java.util.Optional;
import java.util.UUID;

/** Public command facade for enqueue/cancel operations. */
public class BackgroundJobService {
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

    public java.util.List<UUID> matchingIds(String type,String ownerModule,java.util.Set<com.lyreo.platform.jobs.domain.BackgroundJobStatus> statuses) {return repository.matchingIds(type,ownerModule,statuses);}

    /** Reads a job by ID. Returns empty if the job does not exist. */
    public Optional<BackgroundJob> findById(UUID jobId) {
        return repository.findById(jobId);
    }

    public record Page(java.util.List<BackgroundJob> items, long total, int page, int size) {}

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Page list(String status, String type, int page, int size) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100 || !type.matches("(?:[A-Z][A-Z0-9_]{0,63})?"))
            throw new com.lyreo.contracts.errors.RequestValidationException("Invalid job filters or pagination");
        java.util.Set<com.lyreo.platform.jobs.domain.BackgroundJobStatus> statuses;
        try {
            statuses = status.isEmpty() ? java.util.Set.of() : status.equals("ACTIVE")
                ? java.util.Set.of(com.lyreo.platform.jobs.domain.BackgroundJobStatus.QUEUED, com.lyreo.platform.jobs.domain.BackgroundJobStatus.RUNNING,
                    com.lyreo.platform.jobs.domain.BackgroundJobStatus.RETRY_WAIT, com.lyreo.platform.jobs.domain.BackgroundJobStatus.CANCEL_REQUESTED)
                : java.util.Set.of(com.lyreo.platform.jobs.domain.BackgroundJobStatus.valueOf(status));
        } catch (IllegalArgumentException invalid) {
            throw new com.lyreo.contracts.errors.RequestValidationException("Unknown job status");
        }
        return repository.list(statuses, type, page, size);
    }

    public JobHealthCounts healthCounts(java.time.Instant since) {
        return repository.healthCounts(since);
    }

    public Optional<BackgroundJob> latest(String type, String ownerModule, UUID ownerReferenceId) {
        return repository.latest(type, ownerModule, ownerReferenceId);
    }

    /** Hold the generic job fence until the caller's business transaction commits. */
    @org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void fenceForCommit(UUID jobId, String workerId) {
        if (!repository.lockForCommit(jobId, workerId)) {
            throw new JobLeaseLostException("Job ownership changed before business commit");
        }
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
