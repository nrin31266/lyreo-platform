package com.lyreo.contracts.vocabulary;

import java.time.Instant;
import java.util.UUID;

public record VocabularyUnitCompletedEvent(
    UUID sessionId,
    UUID learnerId,
    UUID packId,
    UUID unitId,
    int correctCount,
    int totalCount,
    Instant occurredAt
) {}
