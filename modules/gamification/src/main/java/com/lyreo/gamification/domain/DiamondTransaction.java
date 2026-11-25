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
    Instant createdAt,
    String reason,
    String ruleVersion
) {
    public DiamondTransaction(
        UUID id,
        UUID learnerId,
        Type type,
        int amount,
        String idempotencyKey,
        String referenceType,
        UUID referenceId,
        Instant createdAt
    ) {
        this(id, learnerId, type, amount, idempotencyKey, referenceType, referenceId, createdAt, null, null);
    }

    public enum Type {
        LESSON_REWARD,
        MISSION_REWARD,
        STREAK_REWARD,
        ACHIEVEMENT_REWARD,
        ADMIN_ADJUSTMENT,
        PURCHASE,
        AI_FEATURE_SPEND
    }
}
