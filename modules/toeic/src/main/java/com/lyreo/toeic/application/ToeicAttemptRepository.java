package com.lyreo.toeic.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Persistence port for TOEIC answer keys and learner attempts. */
public interface ToeicAttemptRepository {

    List<QuestionKey> answerKey(UUID testId, Set<UUID> questionIds);

    UUID saveCompletedAttempt(
        UUID learnerId,
        UUID testId,
        String mode,
        ScoreSummary score,
        Map<UUID, String> submittedAnswers,
        List<QuestionKey> answerKey
    );

    record QuestionKey(UUID questionId, Integer part, String correctAnswer) {}

    record ScoreSummary(
        int listeningCorrect,
        int listeningTotal,
        int readingCorrect,
        int readingTotal,
        Integer listeningScaledScore,
        Integer readingScaledScore
    ) {}
}
