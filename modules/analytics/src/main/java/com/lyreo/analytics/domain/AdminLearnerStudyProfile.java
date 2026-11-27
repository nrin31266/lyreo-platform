package com.lyreo.analytics.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Comprehensive learner activity, skill mastery, path progress, badges, and practice breakdown for admin inspection. */
public record AdminLearnerStudyProfile(
    boolean hasHistory,
    AnalyticsOverview.ActivityWindow today,
    AnalyticsOverview.ActivityWindow week,
    AnalyticsOverview.ActivityWindow month,
    List<DailyActivityPoint> dailySeries,
    List<AnalyticsOverview.SkillSummary> skills,
    List<AnalyticsOverview.WeaknessSummary> weaknesses,
    List<EnrolledPathProgress> enrolledPaths,
    List<UnlockedBadge> unlockedAchievements,
    PracticeBreakdown breakdown
) {
    public static AdminLearnerStudyProfile empty(LocalDate todayUtc) {
        return new AdminLearnerStudyProfile(
            false,
            new AnalyticsOverview.ActivityWindow(todayUtc, todayUtc, 0, 0L, 0, 0, 0, 0),
            new AnalyticsOverview.ActivityWindow(todayUtc.minusDays(6), todayUtc, 0, 0L, 0, 0, 0, 0),
            new AnalyticsOverview.ActivityWindow(todayUtc.minusDays(29), todayUtc, 0, 0L, 0, 0, 0, 0),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new PracticeBreakdown(0, 0, 0, 0, 0, 0, 0, null)
        );
    }

    public record DailyActivityPoint(
        LocalDate date,
        long studySeconds,
        int lessonCount,
        int vocabularyReviewCount,
        int grammarAnswerCount,
        int toeicQuestionCount
    ) {}

    public record EnrolledPathProgress(
        UUID pathId,
        String pathName,
        String level,
        String status,
        int completedItems,
        int totalItems,
        Instant startedAt
    ) {}

    public record UnlockedBadge(
        UUID achievementId,
        String code,
        String title,
        String badgeTier,
        UUID badgeAssetId,
        Instant unlockedAt
    ) {}

    public record PracticeBreakdown(
        int lessonsCompleted,
        int lessonsInProgress,
        int vocabCardsTotal,
        int vocabCardsMastered,
        int grammarAttempts,
        int grammarAccuracyPct,
        int toeicAttempts,
        Integer bestToeicScore
    ) {}
}
