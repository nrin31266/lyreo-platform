package com.lyreo.curriculum.domain;

import java.time.Instant;
import java.util.UUID;

public record CurriculumEnrollment(
    UUID learnerId,
    UUID pathId,
    String status,
    Instant startedAt,
    Instant completedAt
) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMPLETED = "COMPLETED";
}
