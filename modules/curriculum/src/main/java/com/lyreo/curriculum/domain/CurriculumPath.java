package com.lyreo.curriculum.domain;

import java.time.Instant;
import java.util.UUID;

public record CurriculumPath(
    UUID id,
    String code,
    String name,
    String level,
    String description,
    String status,
    UUID coverAssetId,
    String accessMode,
    String requiredFeatureKey,
    Instant createdAt,
    Instant updatedAt
) {
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    public CurriculumPath(
        UUID id,
        String code,
        String name,
        String level,
        String description,
        String status,
        Instant createdAt,
        Instant updatedAt
    ) {
        this(id, code, name, level, description, status, null, "PUBLIC", null, createdAt, updatedAt);
    }

    public boolean isPublished() {
        return STATUS_PUBLISHED.equals(status);
    }
}
