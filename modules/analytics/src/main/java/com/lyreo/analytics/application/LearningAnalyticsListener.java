package com.lyreo.analytics.application;

import com.lyreo.analytics.application.port.AnalyticsProjectionRepository;
import com.lyreo.contracts.grammar.GrammarQuestionAnsweredEvent;
import com.lyreo.contracts.lesson.LessonActivityCompletedEvent;
import com.lyreo.contracts.lesson.LessonCompletedEvent;
import com.lyreo.contracts.toeic.ToeicAttemptCompletedEvent;
import com.lyreo.contracts.vocabulary.VocabularyReviewCompletedEvent;
import com.lyreo.contracts.vocabulary.VocabularyUnitCompletedEvent;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Projects cross-module learning facts into analytics read models. */
@Component
public class LearningAnalyticsListener {
    private final AnalyticsService analytics;

    public LearningAnalyticsListener(AnalyticsProjectionRepository projections) {
        this(new AnalyticsService(projections));
    }

    @Autowired
    public LearningAnalyticsListener(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @ApplicationModuleListener
    public void onActivityCompleted(LessonActivityCompletedEvent event) {
        UUID factId = event.factId() != null
            ? event.factId()
            : deterministicFactId("lesson-activity:" + event.learnerId() + ":" + event.activityId());
        analytics.onActivityCompleted(
            factId,
            event.learnerId(),
            event.activityType(),
            event.serverScore(),
            event.occurredAt()
        );
    }

    @ApplicationModuleListener
    public void onLessonCompleted(LessonCompletedEvent event) {
        UUID factId = event.factId() != null
            ? event.factId()
            : deterministicFactId("lesson:" + event.learnerId() + ":" + event.lessonId());
        analytics.onLessonCompleted(factId, event.learnerId(), event.occurredAt());
    }

    @ApplicationModuleListener
    public void onVocabularyReviewed(VocabularyReviewCompletedEvent event) {
        UUID factId = event.reviewId() != null
            ? event.reviewId()
            : deterministicFactId("vocab-review:" + event.learnerId() + ":" + event.headwordId() + ":" + event.occurredAt());
        analytics.onVocabularyReviewed(
            factId,
            event.learnerId(),
            event.headwordId(),
            event.cardId(),
            event.rating(),
            event.correct(),
            event.occurredAt()
        );
    }

    @ApplicationModuleListener
    public void onVocabularyUnitCompleted(VocabularyUnitCompletedEvent event) {
        UUID factId = event.sessionId() != null
            ? event.sessionId()
            : deterministicFactId("vocab-unit:" + event.learnerId() + ":" + event.unitId() + ":" + event.occurredAt());
        analytics.onVocabularyUnitCompleted(
            factId,
            event.learnerId(),
            event.unitId(),
            event.correctCount(),
            event.totalCount(),
            event.occurredAt()
        );
    }

    @ApplicationModuleListener
    public void onGrammarAnswered(GrammarQuestionAnsweredEvent event) {
        UUID factId = (event.attemptId() != null && event.itemId() != null)
            ? deterministicFactId("grammar:" + event.attemptId() + ":" + event.itemId())
            : (event.attemptId() != null
                ? event.attemptId()
                : deterministicFactId("grammar:" + event.learnerId() + ":" + event.occurredAt()));
        analytics.onGrammarAnswered(
            factId,
            event.learnerId(),
            event.topicCatalogId(),
            event.subtopicCatalogId(),
            event.correct(),
            event.occurredAt()
        );
    }

    @ApplicationModuleListener
    public void onToeicCompleted(ToeicAttemptCompletedEvent event) {
        UUID factId = event.attemptId() != null
            ? event.attemptId()
            : deterministicFactId("toeic:" + event.learnerId() + ":" + event.testCatalogId() + ":" + event.occurredAt());
        analytics.onToeicCompleted(
            factId,
            event.learnerId(),
            event.listeningCorrect(),
            event.listeningTotal(),
            event.readingCorrect(),
            event.readingTotal(),
            event.listeningScaledScore(),
            event.readingScaledScore(),
            event.occurredAt()
        );
    }

    private static UUID deterministicFactId(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
