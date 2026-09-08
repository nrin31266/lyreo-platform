package com.lyreo.analytics.application;

import java.time.Instant;
import java.util.UUID;

/**
 * Write-side port for compact learner analytics projections.
 *
 * <p>Analytics never owns detailed practice history. It projects facts emitted by the modules
 * that own those histories so Home/Progress screens can answer common questions cheaply.</p>
 */
public interface AnalyticsProjectionRepository {
    void recordActivity(UUID learnerId, String type, int score, Instant occurredAt);

    void recordLessonCompleted(UUID learnerId, Instant occurredAt);

    void recordVocabularyReview(UUID learnerId, boolean correct, Instant occurredAt);

    void recordGrammarAnswer(UUID learnerId, boolean correct, Instant occurredAt);

    void recordToeic(
        UUID learnerId,
        int listeningCorrect,
        int listeningTotal,
        int readingCorrect,
        int readingTotal,
        Integer listeningScaledScore,
        Integer readingScaledScore,
        Instant occurredAt
    );
}
