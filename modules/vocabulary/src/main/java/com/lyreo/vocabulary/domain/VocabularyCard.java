package com.lyreo.vocabulary.domain;

import java.time.Instant;
import java.util.UUID;

public record VocabularyCard(
    UUID id,
    UUID learnerId,
    UUID headwordId,
    String sourceContextType,
    UUID sourceContextId,
    Instant nextReviewAt,
    double stability,
    double difficulty,
    int lapseCount,
    int reviewCount
) {}
