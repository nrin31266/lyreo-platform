package com.lyreo.analytics.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record AnalyticsOverview(
    boolean hasHistory,
    ActivityWindow today,
    ActivityWindow week,
    List<SkillSummary> skills,
    List<WeaknessSummary> weaknesses
) {
    public static AnalyticsOverview empty(LocalDate todayUtc) {
        return new AnalyticsOverview(
            false,
            new ActivityWindow(todayUtc, todayUtc, 0, 0L, 0, 0, 0, 0),
            new ActivityWindow(todayUtc.minusDays(6), todayUtc, 0, 0L, 0, 0, 0, 0),
            List.of(),
            List.of()
        );
    }

    public record ActivityWindow(
        LocalDate fromDate,
        LocalDate toDate,
        int activeDays,
        long studySeconds,
        int lessonCount,
        int vocabularyReviewCount,
        int grammarAnswerCount,
        int toeicQuestionCount
    ) {}

    public record SkillSummary(
        String skillCode,
        BigDecimal score,
        int sampleCount,
        Instant updatedAt
    ) {}

    public record WeaknessSummary(
        String weaknessType,
        String weaknessKey,
        BigDecimal severity,
        int sampleCount,
        Instant updatedAt
    ) {}
}
