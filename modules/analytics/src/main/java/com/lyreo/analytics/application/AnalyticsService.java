package com.lyreo.analytics.application;

import com.lyreo.analytics.application.port.AnalyticsProjectionRepository;
import com.lyreo.analytics.domain.AdminAnalyticsSummary;
import com.lyreo.analytics.domain.AdminDashboardSummary;
import com.lyreo.analytics.domain.AdminLearnerStudyProfile;
import com.lyreo.analytics.domain.AnalyticsOverview;
import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.lesson.ShadowingAssessedEvent;
import com.lyreo.contracts.toeic.ToeicAttemptCompletedEvent.PartResult;
import com.lyreo.platform.config.application.ConsumerReceiptStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Projects deduplicated learning facts into analytics read models and serves overview queries. */
public class AnalyticsService {
    private static final String CONSUMER_NAME = "analytics.projection";

    private final AnalyticsProjectionRepository projections;
    private final ConsumerReceiptStore receipts;
    private final Clock clock;
    private final JobHealthSource jobHealth;

    public AnalyticsService(AnalyticsProjectionRepository projections) {
        this(projections, null, Clock.systemUTC());
    }

    public AnalyticsService(
        AnalyticsProjectionRepository projections,
        ConsumerReceiptStore receipts,
        Clock clock
    ) {
        this(projections, receipts, clock, null);
    }

    public AnalyticsService(
        AnalyticsProjectionRepository projections,
        ConsumerReceiptStore receipts,
        Clock clock,
        JobHealthSource jobHealth
    ) {
        this.jobHealth = jobHealth;
        this.projections = Objects.requireNonNull(projections, "projections");
        this.receipts = receipts;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Transactional
    public void onActivityCompleted(
        UUID factId,
        UUID learnerId,
        String activityType,
        int serverScore,
        Instant occurredAt
    ) {
        if (!recordReceipt(factId)) {
            return;
        }
        Instant timestamp = occurredAt != null ? occurredAt : clock.instant();
        projections.recordActivity(learnerId, activityType, serverScore, timestamp);

        int boundedScore = Math.max(0, Math.min(100, serverScore));
        int errorScore = 100 - boundedScore;
        String key = activityType == null || activityType.isBlank() ? "GENERAL" : activityType;
        projections.recordWeaknessSample(
            learnerId,
            "LESSON_ACTIVITY",
            key,
            errorScore,
            boundedScore < 60
        );
    }

    @Transactional
    public void onLessonCompleted(UUID factId, UUID learnerId, Instant occurredAt) {
        if (!recordReceipt(factId)) {
            return;
        }
        projections.recordLessonCompleted(learnerId, occurredAt != null ? occurredAt : clock.instant());
    }

    @Transactional
    public void onVocabularyReviewed(
        UUID factId,
        UUID learnerId,
        UUID headwordId,
        UUID cardId,
        String rating,
        boolean correct,
        Instant occurredAt
    ) {
        if (!recordReceipt(factId)) {
            return;
        }
        Instant timestamp = occurredAt != null ? occurredAt : clock.instant();
        boolean isAgain = !correct || "AGAIN".equalsIgnoreCase(rating);
        projections.recordVocabularyReview(learnerId, !isAgain, timestamp);

        String key = headwordId != null
            ? headwordId.toString()
            : (cardId != null ? cardId.toString() : "VOCABULARY");
        projections.recordWeaknessSample(
            learnerId,
            "VOCABULARY",
            key,
            isAgain ? 100 : 0,
            isAgain
        );
    }

    @Transactional
    public void onVocabularyUnitCompleted(
        UUID factId,
        UUID learnerId,
        UUID unitId,
        int correctCount,
        int totalCount,
        Instant occurredAt
    ) {
        if (!recordReceipt(factId)) {
            return;
        }
        Instant timestamp = occurredAt != null ? occurredAt : clock.instant();
        projections.recordVocabularyUnit(learnerId, correctCount, totalCount, timestamp);
        if (totalCount > 0) {
            int errorRate = (int) Math.round(Math.max(0, totalCount - correctCount) * 100.0 / totalCount);
            projections.recordWeaknessSample(
                learnerId,
                "VOCABULARY_UNIT",
                unitId != null ? unitId.toString() : "VOCABULARY_UNIT",
                errorRate,
                correctCount < totalCount
            );
        }
    }

    @Transactional
    public void onGrammarAnswered(
        UUID factId,
        UUID learnerId,
        UUID topicCatalogId,
        UUID subtopicCatalogId,
        boolean correct,
        Instant occurredAt
    ) {
        if (!recordReceipt(factId)) {
            return;
        }
        Instant timestamp = occurredAt != null ? occurredAt : clock.instant();
        projections.recordGrammarAnswer(learnerId, correct, timestamp);

        String weaknessKey = topicCatalogId != null
            ? topicCatalogId.toString()
            : (subtopicCatalogId != null ? subtopicCatalogId.toString() : "GRAMMAR");
        projections.recordWeaknessSample(
            learnerId,
            "GRAMMAR_TOPIC",
            weaknessKey,
            correct ? 0 : 100,
            !correct
        );
    }

    @Transactional
    public void onToeicCompleted(
        UUID factId,
        UUID learnerId,
        int listeningCorrect,
        int listeningTotal,
        int readingCorrect,
        int readingTotal,
        Integer listeningScaledScore,
        Integer readingScaledScore,
        Instant occurredAt,
        List<PartResult> parts
    ) {
        if (!recordReceipt(factId)) {
            return;
        }
        Instant timestamp = occurredAt != null ? occurredAt : clock.instant();
        projections.recordToeic(
            learnerId,
            listeningCorrect,
            listeningTotal,
            readingCorrect,
            readingTotal,
            listeningScaledScore,
            readingScaledScore,
            timestamp
        );

        if (!parts.isEmpty()) {
            for (PartResult part : parts) {
                if (part.part() >= 1 && part.part() <= 7 && part.total() > 0) {
                    int errorRate = percentage(part.total() - part.correct(), part.total());
                    projections.recordWeaknessSample(
                        learnerId, "TOEIC_PART", Integer.toString(part.part()), errorRate, errorRate > 0
                    );
                }
            }
            return;
        }
        if (listeningTotal > 0) {
            int errorRate = (int) Math.round(Math.max(0, listeningTotal - listeningCorrect) * 100.0 / listeningTotal);
            projections.recordWeaknessSample(
                learnerId,
                "TOEIC_SECTION",
                "LISTENING",
                errorRate,
                listeningCorrect < listeningTotal
            );
        }
        if (readingTotal > 0) {
            int errorRate = (int) Math.round(Math.max(0, readingTotal - readingCorrect) * 100.0 / readingTotal);
            projections.recordWeaknessSample(
                learnerId,
                "TOEIC_SECTION",
                "READING",
                errorRate,
                readingCorrect < readingTotal
            );
        }
    }

    @Transactional
    public void onShadowingAssessed(ShadowingAssessedEvent event) {
        if (!recordReceipt(event.attemptId())) return;
        if (event.assessedWords() > 0) {
            int errorRate = percentage(event.mismatchedWords(), event.assessedWords());
            projections.recordWeaknessSample(
                event.learnerId(), "SHADOWING_ASSESSMENT", "WORD_MISMATCH", errorRate, errorRate > 0
            );
        }
        if (event.fluencyScore() != null) {
            int score = Math.clamp(event.fluencyScore(), 0, 100);
            projections.recordWeaknessSample(
                event.learnerId(), "SHADOWING_ASSESSMENT", "FLUENCY", 100 - score, score < 60
            );
        }
    }

    private static int percentage(int count, int total) {
        return Math.clamp((int) Math.round(count * 100.0 / total), 0, 100);
    }

    @Transactional(readOnly = true)
    public AnalyticsOverview overview(UUID learnerId) {
        LocalDate todayUtc = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        return projections.loadOverview(learnerId, todayUtc);
    }

    @Transactional(readOnly = true)
    public AdminAnalyticsSummary platformSummary() {
        LocalDate todayUtc = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        return projections.summarizePlatform(todayUtc);
    }

    @Transactional(readOnly = true)
    public AdminDashboardSummary adminDashboard(int days) {
        if (days != 7 && days != 14 && days != 30) {
            throw new RequestValidationException("days must be 7, 14, or 30");
        }
        LocalDate todayUtc = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        AdminDashboardSummary summary = projections.loadAdminDashboard(todayUtc, days);
        if (jobHealth == null) {
            return summary;
        }
        JobHealthSource.Counts jobs = jobHealth.counts(clock.instant().minusSeconds(86_400L));
        var ops = summary.operations();
        return new AdminDashboardSummary(
            summary.period(),
            summary.kpis(),
            summary.dailySeries(),
            new AdminDashboardSummary.OperationsHealth(
                jobs.active(), jobs.failedSince(), jobs.succeededSince(),
                ops.aiProvidersEnabled(), ops.aiProvidersHealthy(), ops.aiProvidersUnhealthy(),
                ops.aiInvocations24h(), ops.aiErrors24h(), ops.aiAvgLatencyMs24h(),
                ops.lockedAccounts(), ops.pendingAccountSync(), ops.scheduledNotifications()
            ),
            summary.contentInventory(),
            summary.topCurriculumPaths(),
            summary.topWeaknesses()
        );
    }

    @Transactional(readOnly = true)
    public AdminLearnerStudyProfile adminStudyProfile(UUID learnerId) {
        LocalDate todayUtc = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        return projections.loadAdminStudyProfile(learnerId, todayUtc);
    }

    private boolean recordReceipt(UUID factId) {
        return receipts == null || factId == null || receipts.tryRecord(CONSUMER_NAME, factId);
    }
}
