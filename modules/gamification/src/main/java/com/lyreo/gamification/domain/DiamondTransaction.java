package com.lyreo.gamification.domain;

import java.time.Instant;
import java.util.UUID;

public record DiamondTransaction(
    UUID id,
    UUID learnerId,
    Type type,
    int amount,
    String idempotencyKey,
    String referenceType,
    UUID referenceId,
    Instant createdAt
) {
    public enum Type {
        LESSON_REWARD,
        MISSION_REWARD,
        STREAK_REWARD,
        ADMIN_ADJUSTMENT,
        PURCHASE,
        AI_FEATURE_SPEND
    }
}
