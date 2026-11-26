package com.lyreo.curriculum.domain;

import java.time.Instant;
import java.util.UUID;

public record CurriculumPathSummary(
    UUID id,
    String code,
    String name,
    String level,
    String description,
    String status,
    UUID coverAssetId,
    String accessMode,
    String requiredFeatureKey,
    boolean locked,
    String enrollmentStatus,
    int totalItems,
    int requiredItems,
    int completedRequiredItems,
    Instant createdAt,
    Instant updatedAt
) {
    public CurriculumPathSummary(
        UUID id,
        String code,
        String name,
        String level,
        String description,
        String status,
        String enrollmentStatus,
        int totalItems,
        int requiredItems,
        int completedRequiredItems,
        Instant createdAt,
        Instant updatedAt
    ) {
        this(
            id, code, name, level, description, status,
            null, "PUBLIC", null, false,
            enrollmentStatus, totalItems, requiredItems, completedRequiredItems,
            createdAt, updatedAt
        );
    }

    public CurriculumPathSummary withLocked(boolean lockedValue) {
        return new CurriculumPathSummary(
            id, code, name, level, description, status,
            coverAssetId, accessMode, requiredFeatureKey, lockedValue,
            enrollmentStatus, totalItems, requiredItems, completedRequiredItems,
            createdAt, updatedAt
        );
    }
}
