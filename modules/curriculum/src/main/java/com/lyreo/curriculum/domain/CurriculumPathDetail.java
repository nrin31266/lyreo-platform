package com.lyreo.curriculum.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CurriculumPathDetail(
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
    Instant createdAt,
    Instant updatedAt,
    String enrollmentStatus,
    Instant enrolledAt,
    Instant completedAt,
    int completedRequiredItems,
    int totalRequiredItems,
    List<SectionView> sections
) {
    public CurriculumPathDetail(
        UUID id,
        String code,
        String name,
        String level,
        String description,
        String status,
        Instant createdAt,
        Instant updatedAt,
        String enrollmentStatus,
        Instant enrolledAt,
        Instant completedAt,
        int completedRequiredItems,
        int totalRequiredItems,
        List<SectionView> sections
    ) {
        this(
            id, code, name, level, description, status,
            null, "PUBLIC", null, false,
            createdAt, updatedAt, enrollmentStatus, enrolledAt, completedAt,
            completedRequiredItems, totalRequiredItems, sections
        );
    }

    public record SectionView(
        UUID id,
        String title,
        int position,
        List<ItemView> items
    ) {}

    public record ItemView(
        UUID id,
        int position,
        CurriculumItem.ContentType contentType,
        UUID contentReferenceId,
        boolean required,
        String unlockRule,
        String progressStatus,
        Instant completedAt,
        Map<String, Object> config
    ) {
        public ItemView(
            UUID id,
            int position,
            CurriculumItem.ContentType contentType,
            UUID contentReferenceId,
            boolean required,
            String unlockRule,
            String progressStatus,
            Instant completedAt
        ) {
            this(id, position, contentType, contentReferenceId, required, unlockRule, progressStatus, completedAt, Map.of());
        }
    }
}
