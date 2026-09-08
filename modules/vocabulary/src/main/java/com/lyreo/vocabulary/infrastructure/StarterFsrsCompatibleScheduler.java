package com.lyreo.vocabulary.infrastructure;

import com.lyreo.vocabulary.application.SpacedRepetitionScheduler;
import com.lyreo.vocabulary.domain.VocabularyCard;
import java.time.Duration;
import java.time.Instant;

/**
 * Starter policy only, deliberately behind a port. Replace with a validated FSRS
 * implementation after algorithm/library evaluation; do not spread scheduling
 * math through domain/application code.
 */
public final class StarterFsrsCompatibleScheduler implements SpacedRepetitionScheduler {
    @Override
    public ScheduleResult schedule(VocabularyCard card, Rating rating, Instant reviewedAt) {
        double factor = switch (rating) {
            case AGAIN -> 0.5;
            case HARD -> 1.2;
            case GOOD -> 2.0;
            case EASY -> 3.5;
        };
        double newStability = Math.max(0.2, card.stability() * factor);
        double newDifficulty = Math.max(1, Math.min(10,
            card.difficulty() + (rating == Rating.AGAIN ? 0.5 : rating == Rating.EASY ? -0.3 : 0)));
        long days = Math.max(1, Math.round(newStability));
        return new ScheduleResult(reviewedAt.plus(Duration.ofDays(days)), newStability, newDifficulty);
    }
}
