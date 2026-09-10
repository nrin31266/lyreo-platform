package com.lyreo.platform.jobs.application;

import com.lyreo.contracts.jobs.JobProgressChangedEvent;
import com.lyreo.platform.jobs.domain.BackgroundJob;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

/**
 * Performs short durable job state transitions and publishes their integration event in the
 * same database transaction.
 *
 * <p>Long-running handlers must never run inside this transaction. The transaction only covers
 * one job-table mutation plus Event Publication Registry registration. This gives Spring Modulith
 * a real transaction to attach {@code @ApplicationModuleListener} deliveries to without holding
 * database locks while an AI/network call is in flight.</p>
 */
public class JobStateTransitionService {
    private final BackgroundJobRepository repository;
    private final ApplicationEventPublisher events;

    public JobStateTransitionService(
        BackgroundJobRepository repository,
        ApplicationEventPublisher events
    ) {
        this.repository = repository;
        this.events = events;
    }

    @Transactional
    public CancellationResult requestCancellation(UUID jobId) {
        CancellationResult result = repository.requestCancellation(jobId);
        if (result != CancellationResult.ACCEPTED) {
            return result;
        }
        BackgroundJob current = repository.findById(jobId).orElseThrow();
        publish(
            current,
            current.status().name(),
            current.currentStep(),
            current.progressPercent()
        );
        return CancellationResult.ACCEPTED;
    }

    @Transactional
    public boolean progress(
        BackgroundJob job,
        String workerId,
        String step,
        int percent
    ) {
        int bounded = Math.max(0, Math.min(100, percent));
        if (!repository.updateProgress(job.id(), workerId, step, bounded)) {
            return false;
        }
        publish(job, "RUNNING", step, bounded);
        return true;
    }

    @Transactional
    public boolean succeeded(BackgroundJob job, String workerId) {
        if (!repository.markSucceeded(job.id(), workerId)) {
            return false;
        }
        publish(job, "SUCCEEDED", job.currentStep(), 100);
        return true;
    }

    @Transactional
    public boolean cancelled(
        BackgroundJob job,
        String workerId,
        String step,
        int percent
    ) {
        if (!repository.markCancelled(job.id(), workerId)) {
            return false;
        }
        publish(job, "CANCELLED", step, percent);
        return true;
    }

    @Transactional
    public boolean failed(
        BackgroundJob job,
        String workerId,
        String errorCode,
        String errorMessage,
        String step,
        int percent
    ) {
        if (!repository.markFailed(job.id(), workerId, errorCode, errorMessage)) {
            return false;
        }
        publish(job, "FAILED", step, percent);
        return true;
    }

    @Transactional
    public boolean retryWaiting(
        BackgroundJob job,
        String workerId,
        Instant nextRetryAt,
        String errorCode,
        String errorMessage,
        String step,
        int percent
    ) {
        if (!repository.scheduleRetry(
            job.id(), workerId, nextRetryAt, errorCode, errorMessage
        )) {
            return false;
        }
        publish(job, "RETRY_WAIT", step, percent);
        return true;
    }


    /**
     * Recovers expired leases and publishes terminal cancellation/requeue visibility in the same
     * transaction. A requeued job event is operational; a cancelled event also drives owning
     * module lifecycle cleanup.
     */
    @Transactional
    public int recoverExpiredLeases(Instant now) {
        var recovered = repository.recoverExpiredLeases(now);
        for (BackgroundJob job : recovered) {
            publish(job, job.status().name(), job.currentStep(), job.progressPercent());
        }
        return recovered.size();
    }

    private void publish(BackgroundJob job, String status, String step, int percent) {
        events.publishEvent(new JobProgressChangedEvent(
            job.id(),
            job.jobType(),
            status,
            step,
            Math.max(0, Math.min(100, percent)),
            Instant.now()
        ));
    }
}
