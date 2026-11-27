package com.lyreo.contracts.vocabulary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record VocabularyUnitCompletedEvent(
    UUID sessionId,
    UUID learnerId,
    UUID packId,
    UUID unitId,
    int correctCount,
    int totalCount,
    Instant occurredAt,
    List<UUID> completedEntryIds
) {
    public VocabularyUnitCompletedEvent {
        completedEntryIds = completedEntryIds == null ? List.of() : List.copyOf(completedEntryIds);
    }

    public VocabularyUnitCompletedEvent(
        UUID sessionId,
        UUID learnerId,
        UUID packId,
        UUID unitId,
        int correctCount,
        int totalCount,
        Instant occurredAt
    ) {
        this(sessionId, learnerId, packId, unitId, correctCount, totalCount, occurredAt, List.of());
    }
}
