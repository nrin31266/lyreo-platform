package com.lyreo.contracts.grammar;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GrammarSessionCompletedEvent(
    UUID sessionId,
    UUID learnerId,
    UUID catalogId,
    String catalogKind,
    int correctCount,
    int totalCount,
    Instant occurredAt,
    UUID topicId,
    List<UUID> subtopicIds
) {
    public GrammarSessionCompletedEvent {
        subtopicIds = subtopicIds == null ? List.of() : List.copyOf(subtopicIds);
    }

    public GrammarSessionCompletedEvent(
        UUID sessionId,
        UUID learnerId,
        UUID catalogId,
        String catalogKind,
        int correctCount,
        int totalCount,
        Instant occurredAt
    ) {
        this(sessionId, learnerId, catalogId, catalogKind, correctCount, totalCount, occurredAt, null, List.of());
    }
}
