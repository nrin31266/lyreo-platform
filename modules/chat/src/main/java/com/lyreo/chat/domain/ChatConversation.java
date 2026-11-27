package com.lyreo.chat.domain;

import java.time.Instant;
import java.util.UUID;

public record ChatConversation(
    UUID id,
    UUID learnerId,
    String title,
    Instant createdAt,
    Instant updatedAt,
    String lastMessagePreview,
    int messageCount
) {
    public ChatConversation(
        UUID id,
        UUID learnerId,
        String title,
        Instant createdAt,
        Instant updatedAt
    ) {
        this(id, learnerId, title, createdAt, updatedAt, null, 0);
    }
}
