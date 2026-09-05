package com.lyreo.gamification.application;

import com.lyreo.contracts.grammar.GrammarQuestionAnsweredEvent;
import com.lyreo.contracts.lesson.LessonCompletedEvent;
import com.lyreo.contracts.toeic.ToeicAttemptCompletedEvent;
import com.lyreo.contracts.vocabulary.VocabularyReviewCompletedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
public final class MissionEventListener {
    private final MissionProgressService missions;

    public MissionEventListener(MissionProgressService missions) {
        this.missions = missions;
    }

    @ApplicationModuleListener
    public void lesson(LessonCompletedEvent event) {
        missions.increment(
            event.learnerId(),
            "LESSON_COMPLETED",
            event.occurredAt(),
            1
        );
    }

    @ApplicationModuleListener
    public void vocabulary(VocabularyReviewCompletedEvent event) {
        missions.increment(
            event.learnerId(),
            "VOCABULARY_REVIEW",
            event.occurredAt(),
            1
        );
    }

    @ApplicationModuleListener
    public void grammar(GrammarQuestionAnsweredEvent event) {
        missions.increment(
            event.learnerId(),
            "GRAMMAR_ANSWER",
            event.occurredAt(),
            1
        );
    }

    @ApplicationModuleListener
    public void toeic(ToeicAttemptCompletedEvent event) {
        if (!"DRILL".equals(event.mode())) return;
        missions.increment(
            event.learnerId(),
            "TOEIC_DRILL_COMPLETED",
            event.occurredAt(),
            1
        );
    }
}
