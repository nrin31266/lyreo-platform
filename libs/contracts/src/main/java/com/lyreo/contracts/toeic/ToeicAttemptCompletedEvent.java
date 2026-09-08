package com.lyreo.contracts.toeic;

import java.time.Instant;
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
    UUID testId,
    String mode,
    int listeningCorrect,
    int listeningTotal,
    int readingCorrect,
    int readingTotal,
    Integer listeningScaledScore,
    Integer readingScaledScore,
    Instant occurredAt
) {}
