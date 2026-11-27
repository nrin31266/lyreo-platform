package com.lyreo.gamification.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LearnerStreak(
    UUID learnerId,
    int currentStreak,
    int longestStreak,
    LocalDate lastStudyDate,
    Instant updatedAt
) {
    public static LearnerStreak empty(UUID learnerId) {
        return new LearnerStreak(learnerId, 0, 0, null, null);
    }
}
