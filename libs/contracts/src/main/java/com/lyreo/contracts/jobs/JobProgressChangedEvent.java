package com.lyreo.contracts.jobs;

import java.time.Instant;
import java.util.UUID;

public record JobProgressChangedEvent(
    UUID jobId, String jobType, String status, String currentStep,
    int progressPercent, Instant occurredAt) {}
