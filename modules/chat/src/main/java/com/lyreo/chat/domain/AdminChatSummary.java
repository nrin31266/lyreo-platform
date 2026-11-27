package com.lyreo.chat.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Privacy-preserving tutor telemetry for administrators (excludes message bodies and titles). */
public record AdminChatSummary(
    long totalConversations,
    long activeLearners,
    long userTurnsToday,
    long userTurns7d,
    List<ConversationTelemetry> recentConversations
) {
    public record ConversationTelemetry(
        UUID conversationId,
        UUID learnerId,
        int messageCount,
        Instant createdAt,
        Instant updatedAt
    ) {}
}
