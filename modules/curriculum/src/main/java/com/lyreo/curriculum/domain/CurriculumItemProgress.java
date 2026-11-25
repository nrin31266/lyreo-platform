package com.lyreo.curriculum.domain;

import java.time.Instant;
import java.util.UUID;

public record CurriculumItemProgress(
    UUID learnerId,
    UUID itemId,
    String status,
    Instant startedAt,
    Instant completedAt
) {
    public static final String STATUS_LOCKED = "LOCKED";
    public static final String STATUS_UNLOCKED = "UNLOCKED";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";
}
