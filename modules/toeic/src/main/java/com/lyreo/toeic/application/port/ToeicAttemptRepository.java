package com.lyreo.toeic.application.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistence port for TOEIC answer keys and learner attempts. */
public interface ToeicAttemptRepository {

    Optional<TestAccess> findActiveTest(UUID catalogId);

    List<QuestionKey> answerKey(UUID testVersionId, Set<UUID> placementIds);

    UUID saveCompletedAttempt(
        UUID learnerId,
        UUID testVersionId,
        String mode,
        ScoreSummary score,
        Map<UUID, String> submittedAnswers,
        List<QuestionKey> answerKey
    );

    record TestAccess(
        UUID catalogId,
        UUID testVersionId,
        String publicationStatus,
        String accessMode,
        String requiredFeatureKey
    ) {}

    record QuestionKey(UUID placementId, Integer part, String correctAnswer) {}

    record ScoreSummary(
        int listeningCorrect,
        int listeningTotal,
        int readingCorrect,
        int readingTotal,
        Integer listeningScaledScore,
        Integer readingScaledScore
    ) {}
}
