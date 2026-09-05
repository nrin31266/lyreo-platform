package com.lyreo.grammar.application;

import com.lyreo.grammar.domain.GrammarQuestion;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for the curated/imported grammar bank and append-only learner attempts. */
public interface GrammarPracticeRepository {
    List<GrammarQuestion> findPracticeQuestions(PracticeFilter filter, int limit);

    Optional<GrammarQuestion> findQuestion(UUID questionId);

    UUID saveAttempt(
        UUID learnerId,
        UUID questionId,
        String submittedAnswer,
        boolean correct,
        Instant answeredAt
    );

    record PracticeFilter(
        UUID topicId,
        UUID subtopicId,
        UUID bankSetId,
        Integer difficultyLevel
    ) {}
}
