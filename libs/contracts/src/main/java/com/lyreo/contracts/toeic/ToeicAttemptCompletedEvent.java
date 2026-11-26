package com.lyreo.contracts.toeic;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cross-module TOEIC completion fact.
 *
 * <p>Raw counts are always available because Lyreo computes them from imported answer keys.
 * Scaled TOEIC scores stay nullable until a curated conversion table is imported; consumers must
 * not invent a linear 5–495 conversion.</p>
 */
public record ToeicAttemptCompletedEvent(
    UUID learnerId,
    UUID attemptId,
    UUID testCatalogId,
    String mode,
    int listeningCorrect,
    int listeningTotal,
    int readingCorrect,
    int readingTotal,
    Integer listeningScaledScore,
    Integer readingScaledScore,
    Instant occurredAt,
    List<PartResult> parts
) {
    public ToeicAttemptCompletedEvent {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }

    public ToeicAttemptCompletedEvent(
        UUID learnerId, UUID attemptId, UUID testCatalogId, String mode,
        int listeningCorrect, int listeningTotal, int readingCorrect, int readingTotal,
        Integer listeningScaledScore, Integer readingScaledScore, Instant occurredAt
    ) {
        this(learnerId, attemptId, testCatalogId, mode, listeningCorrect, listeningTotal,
            readingCorrect, readingTotal, listeningScaledScore, readingScaledScore, occurredAt, List.of());
    }

    public record PartResult(int part, int correct, int total) {}
}
