package com.lyreo.learner.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LearnerDirectory(List<Entry> items, long total, int page, int size) {
    public enum Status { PENDING, COMPLETED }

    public record Entry(
        UUID learnerId, String displayName, String email, Instant firstSeenAt,
        Status onboardingStatus, Instant onboardingCompletedAt,
        String goal, String currentLevel, Integer dailyMinutes, String focusArea,
        String avatarPreset, UUID avatarUploadId, Integer toeicTargetScore
    ) {}
}
