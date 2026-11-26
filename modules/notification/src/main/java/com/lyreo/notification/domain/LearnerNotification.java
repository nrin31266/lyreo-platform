package com.lyreo.notification.domain;

import java.time.Instant;
import java.util.UUID;

public record LearnerNotification(
    UUID id,
    String title,
    String body,
    String category,
    String targetAudience,
    String status,
    Instant publishedAt,
    Instant expiresAt,
    String createdBy,
    Instant createdAt,
    Instant updatedAt
) {}
