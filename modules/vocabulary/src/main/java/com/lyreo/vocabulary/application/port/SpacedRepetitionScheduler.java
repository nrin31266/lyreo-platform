package com.lyreo.vocabulary.application.port;

import com.lyreo.vocabulary.domain.VocabularyCard;
import java.time.Instant;

public interface SpacedRepetitionScheduler {
    ScheduleResult schedule(VocabularyCard card, Rating rating, Instant reviewedAt);

    default ScheduleResult schedule(
        double stability,
        double difficulty,
        int reviewCount,
        int lapseCount,
        Rating rating,
        Instant reviewedAt
    ) {
        return schedule(
            new VocabularyCard(null, null, null, null, null, reviewedAt, stability, difficulty, lapseCount, reviewCount),
            rating,
            reviewedAt
        );
    }

    enum Rating { AGAIN, HARD, GOOD, EASY }

    record ScheduleResult(
        Instant nextReviewAt,
        double stability,
        double difficulty
    ) {}
}
