package com.lyreo.platform.jobs.domain;

import java.time.Instant;
import java.util.UUID;

public record BackgroundJob(
    UUID id,
    String jobType,
    String ownerModule,
    UUID ownerReferenceId,
    BackgroundJobStatus status,
    int priority,
    String currentStep,
    int progressPercent,
    int attemptCount,
    int maxAttempts,
    Instant nextRetryAt,
    String leaseOwner,
    Instant leaseUntil,
    Instant cancelRequestedAt,
    String errorMessage
) {
    public boolean cancellationRequested() {
        return status == BackgroundJobStatus.CANCEL_REQUESTED || cancelRequestedAt != null;
    }
}
