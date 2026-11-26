package com.lyreo.analytics.application.port;

import com.lyreo.analytics.domain.AdminAnalyticsSummary;
import com.lyreo.analytics.domain.AdminLearnerStudyProfile;
import com.lyreo.analytics.domain.AnalyticsOverview;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Write-side and read-side port for compact learner analytics projections.
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

    default void recordVocabularyUnit(UUID learnerId, int correctCount, int totalCount, Instant occurredAt) {
    }

    default void recordWeaknessSample(
        UUID learnerId,
        String weaknessType,
        String weaknessKey,
        int errorScore,
        boolean insertIfMissing
    ) {
    }

    default AnalyticsOverview loadOverview(UUID learnerId, LocalDate todayUtc) {
        return AnalyticsOverview.empty(todayUtc);
    }

    default AdminAnalyticsSummary summarizePlatform(LocalDate todayUtc) {
        return new AdminAnalyticsSummary(0, 0, 0L, 0, 0, 0, 0, List.of(), List.of());
    }

    default AdminLearnerStudyProfile loadAdminStudyProfile(UUID learnerId, LocalDate todayUtc) {
        return AdminLearnerStudyProfile.empty(todayUtc);
    }
}
