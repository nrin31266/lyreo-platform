package com.lyreo.vocabulary;

import static org.assertj.core.api.Assertions.assertThat;

import com.lyreo.vocabulary.application.SpacedRepetitionScheduler;
import com.lyreo.vocabulary.domain.VocabularyCard;
import com.lyreo.vocabulary.infrastructure.StarterFsrsCompatibleScheduler;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StarterSchedulerTest {

    private final StarterFsrsCompatibleScheduler scheduler = new StarterFsrsCompatibleScheduler();

    @Test
    void easyShouldScheduleLaterAndReduceDifficulty() {
        Instant reviewedAt = Instant.parse("2026-09-06T00:00:00Z");
        VocabularyCard card = new VocabularyCard(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "LESSON", UUID.randomUUID(), reviewedAt, 2.0, 5.0, 0, 3
        );

        var result = scheduler.schedule(card, SpacedRepetitionScheduler.Rating.EASY, reviewedAt);

        assertThat(result.nextReviewAt()).isAfter(reviewedAt.plusSeconds(24 * 60 * 60));
        assertThat(result.stability()).isGreaterThan(card.stability());
        assertThat(result.difficulty()).isLessThan(card.difficulty());
    }

    @Test
    void againShouldNeverProduceInvalidDifficultyOrPastReview() {
        Instant reviewedAt = Instant.parse("2026-09-06T00:00:00Z");
        VocabularyCard card = new VocabularyCard(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "LEXICON", UUID.randomUUID(), reviewedAt, 0.2, 10.0, 4, 12
        );

        var result = scheduler.schedule(card, SpacedRepetitionScheduler.Rating.AGAIN, reviewedAt);

        assertThat(result.nextReviewAt()).isAfter(reviewedAt);
        assertThat(result.stability()).isGreaterThanOrEqualTo(0.2);
        assertThat(result.difficulty()).isBetween(1.0, 10.0);
    }
}
