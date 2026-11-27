package com.lyreo.contracts.vocabulary;

import java.time.Instant;
import java.util.UUID;

public record VocabularyReviewCompletedEvent(
    UUID reviewId,
    UUID learnerId,
    UUID cardId,
    UUID headwordId,
    String rating,
    boolean correct,
    Instant occurredAt
) {
    public VocabularyReviewCompletedEvent(
        UUID learnerId,
        UUID headwordId,
        String rating,
        boolean correct,
        Instant occurredAt
    ) {
        this(UUID.randomUUID(), learnerId, null, headwordId, rating, correct, occurredAt);
    }
}
