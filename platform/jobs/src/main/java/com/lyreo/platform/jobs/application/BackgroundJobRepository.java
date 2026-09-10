package com.lyreo.platform.jobs.application;

import com.lyreo.platform.jobs.domain.BackgroundJob;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BackgroundJobRepository {
    UUID enqueue(String jobType, String ownerModule, UUID ownerReferenceId, int priority, int maxAttempts, String configSnapshotJson);
    List<BackgroundJob> claim(String workerId, int limit, Duration lease);
    Optional<BackgroundJob> findById(UUID id);
    CancellationResult requestCancellation(UUID id);
    boolean isCancellationRequested(UUID id);

    /** Lease-aware writes are fencing operations: stale workers must not mutate recovered jobs. */
    boolean heartbeat(UUID id, String workerId, Duration lease);
    boolean updateProgress(UUID id, String workerId, String step, int percent);
    boolean markSucceeded(UUID id, String workerId);
    boolean markCancelled(UUID id, String workerId);
    boolean markFailed(UUID id, String workerId, String errorCode, String errorMessage);
    boolean scheduleRetry(UUID id, String workerId, Instant nextRetryAt, String errorCode, String errorMessage);

    List<BackgroundJob> recoverExpiredLeases(Instant now);
}
