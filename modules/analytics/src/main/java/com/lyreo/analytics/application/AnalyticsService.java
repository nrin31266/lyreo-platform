package com.lyreo.analytics.application;

import com.lyreo.analytics.application.port.AnalyticsProjectionRepository;
import com.lyreo.analytics.domain.AdminAnalyticsSummary;
import com.lyreo.analytics.domain.AnalyticsOverview;
import com.lyreo.platform.config.application.ConsumerReceiptStore;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Projects deduplicated learning facts into analytics read models and serves overview queries. */
public class AnalyticsService {
    private static final String CONSUMER_NAME = "analytics.projection";

    private final AnalyticsProjectionRepository projections;
    private final ConsumerReceiptStore receipts;
    private final Clock clock;

    public AnalyticsService(AnalyticsProjectionRepository projections) {
        this(projections, null, Clock.systemUTC());
    }

    public AnalyticsService(
        AnalyticsProjectionRepository projections,
        ConsumerReceiptStore receipts,
        Clock clock
    ) {
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
        Instant occurredAt
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

    private boolean recordReceipt(UUID factId) {
        return receipts == null || factId == null || receipts.tryRecord(CONSUMER_NAME, factId);
    }
}
