package com.lyreo.analytics.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record AdminAnalyticsSummary(
    int activeLearnersToday,
    int activeLearners7d,
    long studySeconds7d,
    int lessonsCompleted7d,
    int vocabularyReviews7d,
    int grammarAnswers7d,
    int toeicQuestions7d,
    List<DailyAggregate> dailySeries,
    List<WeaknessHotspot> topWeaknesses
) {
    public record DailyAggregate(
        LocalDate activityDate,
        int activeLearners,
        long studySeconds,
        int lessonCount,
        int vocabularyReviewCount,
        int grammarAnswerCount,
        int toeicQuestionCount
    ) {}

    public record WeaknessHotspot(
        String weaknessType,
        String weaknessKey,
        int affectedLearners,
        BigDecimal averageSeverity,
        int totalSamples,
        Instant lastUpdatedAt,
        String label,
        String labelEn
    ) {
        public WeaknessHotspot(
            String weaknessType, String weaknessKey, int affectedLearners,
            BigDecimal averageSeverity, int totalSamples, Instant lastUpdatedAt
        ) {
            this(weaknessType, weaknessKey, affectedLearners, averageSeverity, totalSamples, lastUpdatedAt, null, null);
        }
    }
}
