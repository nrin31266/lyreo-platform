package com.lyreo.vocabulary.application;

import com.lyreo.vocabulary.domain.VocabularyCard;
import java.time.Instant;

public interface SpacedRepetitionScheduler {
    ScheduleResult schedule(VocabularyCard card, Rating rating, Instant reviewedAt);

    enum Rating { AGAIN, HARD, GOOD, EASY }

    record ScheduleResult(
        Instant nextReviewAt,
        double stability,
        double difficulty
    ) {}
}
