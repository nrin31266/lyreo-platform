package com.lyreo.analytics.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record AdminDashboardSummary(
    Period period,
    Kpis kpis,
    List<DailyPoint> dailySeries,
    OperationsHealth operations,
    ContentInventory contentInventory,
    List<CurriculumPathMetric> topCurriculumPaths,
    List<AdminAnalyticsSummary.WeaknessHotspot> topWeaknesses
) {
    public record Period(int days, LocalDate fromDate, LocalDate toDate) {}

    public record Kpis(
        int activeLearners,
        int previousActiveLearners,
        int activeLearnersToday,
        int totalLearners,
        int newLearners,
        int previousNewLearners,
        int onboardedLearners,
        int activePlusLearners,
        int plusStartedInPeriod,
        long studySeconds,
        long previousStudySeconds,
        int lessonsCompleted,
        int vocabularyReviews,
        int grammarAnswers,
        int toeicQuestions,
        int practiceTotal,
        int previousPracticeTotal,
        int aiTutorTurns,
        int aiTutorTurnsToday,
        int aiTutorActiveLearners,
        long aiTutorTotalConversations
    ) {}

    public record DailyPoint(
        LocalDate activityDate,
        int activeLearners,
        int newLearners,
        long studySeconds,
        int lessonCount,
        int vocabularyReviewCount,
        int grammarAnswerCount,
        int toeicQuestionCount
    ) {}

    public record OperationsHealth(
        int jobsRunning,
        int jobsFailed24h,
        int jobsSucceeded24h,
        int aiProvidersEnabled,
        int aiProvidersHealthy,
        int aiProvidersUnhealthy,
        int aiInvocations24h,
        int aiErrors24h,
        long aiAvgLatencyMs24h,
        int lockedAccounts,
        int pendingAccountSync,
        int scheduledNotifications
    ) {}

    public record ContentInventory(
        int lessonsPublished,
        int lessonsDraft,
        int curriculumPathsPublished,
        int curriculumPathsDraft,
        int curriculumEnrollmentsActive,
        int curriculumEnrollmentsCompleted,
        int vocabularyPacksPublished,
        int vocabularyUnitsTotal,
        int grammarTopicsPublished,
        int toeicTestsPublished,
        int lexiconHeadwordsTotal
    ) {}

    public record CurriculumPathMetric(
        UUID pathId,
        String code,
        String name,
        String level,
        String status,
        int enrolledLearners,
        int completedLearners,
        int averageCompletionPercent
    ) {}

    public static AdminDashboardSummary empty(LocalDate todayUtc, int days) {
        LocalDate from = todayUtc.minusDays(days - 1L);
        return new AdminDashboardSummary(
            new Period(days, from, todayUtc),
            new Kpis(0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, 0L, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0L),
            List.of(),
            new OperationsHealth(0, 0, 0, 0, 0, 0, 0, 0, 0L, 0, 0, 0),
            new ContentInventory(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            List.of(),
            List.of()
        );
    }
}
