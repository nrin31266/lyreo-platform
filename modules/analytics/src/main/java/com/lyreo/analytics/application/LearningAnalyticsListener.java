package com.lyreo.analytics.application;

import com.lyreo.contracts.grammar.GrammarQuestionAnsweredEvent;
import com.lyreo.contracts.lesson.LessonActivityCompletedEvent;
import com.lyreo.contracts.lesson.LessonCompletedEvent;
import com.lyreo.contracts.toeic.ToeicAttemptCompletedEvent;
import com.lyreo.contracts.vocabulary.VocabularyReviewCompletedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Projects cross-module learning facts into analytics read models. */
@Component
public class LearningAnalyticsListener {
    private final AnalyticsProjectionRepository projections;

    public LearningAnalyticsListener(AnalyticsProjectionRepository projections) {
        this.projections = projections;
    }

    @ApplicationModuleListener
    public void onActivityCompleted(LessonActivityCompletedEvent event) {
        projections.recordActivity(
            event.learnerId(),
            event.activityType(),
            event.serverScore(),
            event.occurredAt()
        );
    }

    @ApplicationModuleListener
    public void onLessonCompleted(LessonCompletedEvent event) {
        projections.recordLessonCompleted(event.learnerId(), event.occurredAt());
    }

    @ApplicationModuleListener
    public void onVocabularyReviewed(VocabularyReviewCompletedEvent event) {
        projections.recordVocabularyReview(
            event.learnerId(),
            event.correct(),
            event.occurredAt()
        );
    }

    @ApplicationModuleListener
    public void onGrammarAnswered(GrammarQuestionAnsweredEvent event) {
        projections.recordGrammarAnswer(
            event.learnerId(),
            event.correct(),
            event.occurredAt()
        );
    }

    @ApplicationModuleListener
    public void onToeicCompleted(ToeicAttemptCompletedEvent event) {
        projections.recordToeic(
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
}
