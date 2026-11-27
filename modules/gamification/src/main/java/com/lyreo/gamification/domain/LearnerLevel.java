package com.lyreo.gamification.domain;

import java.time.Instant;
import java.util.UUID;

public record LearnerLevel(
    UUID learnerId,
    long xp,
    int level,
    Instant updatedAt
) {
    public static LearnerLevel empty(UUID learnerId) {
        return new LearnerLevel(learnerId, 0L, 1, null);
    }
}
