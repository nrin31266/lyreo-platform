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
    String enrollmentStatus,
    int totalItems,
    int requiredItems,
    int completedRequiredItems,
    Instant createdAt,
    Instant updatedAt
) {}
