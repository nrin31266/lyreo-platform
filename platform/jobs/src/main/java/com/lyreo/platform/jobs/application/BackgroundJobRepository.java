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
    BackgroundJobService.Page list(java.util.Set<com.lyreo.platform.jobs.domain.BackgroundJobStatus> statuses, String type, int page, int size);
    Optional<BackgroundJob> latest(String type, String ownerModule, UUID ownerReferenceId);
    default List<UUID> matchingIds(String type,String ownerModule,java.util.Set<com.lyreo.platform.jobs.domain.BackgroundJobStatus> statuses) {return List.of();}
    default JobHealthCounts healthCounts(Instant since) { return JobHealthCounts.EMPTY; }
    CancellationResult requestCancellation(UUID id);
    boolean isCancellationRequested(UUID id);

    /** Lease-aware writes are fencing operations: stale workers must not mutate recovered jobs. */
    boolean heartbeat(UUID id, String workerId, Duration lease);
    boolean lockForCommit(UUID id, String workerId);
    boolean updateProgress(UUID id, String workerId, String step, int percent);
    boolean markSucceeded(UUID id, String workerId);
    boolean markCancelled(UUID id, String workerId);
    boolean markFailed(UUID id, String workerId, String errorCode, String errorMessage);
    boolean scheduleRetry(UUID id, String workerId, Instant nextRetryAt, String errorCode, String errorMessage);

    List<BackgroundJob> recoverExpiredLeases(Instant now);
}
