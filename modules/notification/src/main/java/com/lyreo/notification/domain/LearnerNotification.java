package com.lyreo.notification.domain;

import java.time.Instant;
import java.util.List;
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
    Instant updatedAt,
    List<UUID> recipientIds,
    List<String> recipientLabels,
    boolean read,
    Instant readAt
) {
    public LearnerNotification(
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
        Instant updatedAt,
        List<UUID> recipientIds,
        List<String> recipientLabels
    ) {
        this(
            id,
            title,
            body,
            category,
            targetAudience,
            status,
            publishedAt,
            expiresAt,
            createdBy,
            createdAt,
            updatedAt,
            recipientIds,
            recipientLabels,
            false,
            null
        );
    }
}
