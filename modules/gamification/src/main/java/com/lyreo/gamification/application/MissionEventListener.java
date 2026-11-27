package com.lyreo.gamification.application;

import com.lyreo.contracts.grammar.GrammarQuestionAnsweredEvent;
import com.lyreo.contracts.grammar.GrammarSessionCompletedEvent;
import com.lyreo.contracts.lesson.LessonCompletedEvent;
import com.lyreo.contracts.lesson.ShadowingAssessedEvent;
import com.lyreo.contracts.toeic.ToeicAttemptCompletedEvent;
import com.lyreo.contracts.vocabulary.VocabularyReviewCompletedEvent;
import com.lyreo.contracts.vocabulary.VocabularyUnitCompletedEvent;
import com.lyreo.gamification.domain.LearnerStreak;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
public class MissionEventListener {
    private final MissionProgressService missions;
    private final StreakService streaks;
    private final AchievementService achievements;

    public MissionEventListener(MissionProgressService missions) {
        this(missions, null, null);
    }

    @Autowired
    public MissionEventListener(
        MissionProgressService missions,
        StreakService streaks,
        AchievementService achievements
    ) {
        this.missions = missions;
        this.streaks = streaks;
        this.achievements = achievements;
    }

    @ApplicationModuleListener
    public void lesson(LessonCompletedEvent event) {
        UUID factId = event.factId() != null
            ? event.factId()
            : deterministicFactId("lesson:" + event.learnerId() + ":" + event.lessonId());
        missions.increment(
            factId,
            event.learnerId(),
            "LESSON_COMPLETED",
            event.occurredAt(),
            1
        );
        recordStreak(factId, event.learnerId(), event.occurredAt());
        if (achievements != null) {
            achievements.recordLessonCompleted(factId, event.learnerId(), event.lessonId());
        }
    }

    @ApplicationModuleListener
    public void vocabulary(VocabularyReviewCompletedEvent event) {
        UUID factId = event.reviewId() != null
            ? event.reviewId()
            : deterministicFactId("vocab-review:" + event.learnerId() + ":" + event.headwordId() + ":" + event.occurredAt());
        missions.increment(
            factId,
            event.learnerId(),
            "VOCABULARY_REVIEW",
            event.occurredAt(),
            1
        );
        recordStreak(factId, event.learnerId(), event.occurredAt());
        if (achievements != null) {
            achievements.recordMetricProgress(factId, event.learnerId(), AchievementService.METRIC_VOCABULARY_REVIEW, 1);
        }
    }

    @ApplicationModuleListener
    public void shadowing(ShadowingAssessedEvent event) {
        UUID factId = event.attemptId() != null
            ? event.attemptId()
            : deterministicFactId("shadowing:" + event.learnerId());
        Instant occurredAt = Instant.now();
        missions.increment(
            factId,
            event.learnerId(),
            "SHADOWING_SENTENCE",
            occurredAt,
            1
        );
        recordStreak(factId, event.learnerId(), occurredAt);
        if (achievements != null) {
            achievements.recordMetricProgress(
                factId,
                event.learnerId(),
                AchievementService.METRIC_SHADOWING_SENTENCE,
                1
            );
        }
    }

    @ApplicationModuleListener
    public void grammar(GrammarQuestionAnsweredEvent event) {
        UUID factId = grammarFactId(event);
        missions.increment(
            factId,
            event.learnerId(),
            "GRAMMAR_ANSWER",
            event.occurredAt(),
            1
        );
        recordStreak(factId, event.learnerId(), event.occurredAt());
        if (achievements != null) {
            achievements.recordMetricProgress(
                factId,
                event.learnerId(),
                AchievementService.METRIC_GRAMMAR_ANSWER,
                1
            );
        }
    }

    @ApplicationModuleListener
    public void toeic(ToeicAttemptCompletedEvent event) {
        UUID factId = event.attemptId() != null
            ? event.attemptId()
            : deterministicFactId("toeic:" + event.learnerId() + ":" + event.testCatalogId() + ":" + event.occurredAt());
        if ("DRILL".equals(event.mode())) {
            missions.increment(
                factId,
                event.learnerId(),
                "TOEIC_DRILL_COMPLETED",
                event.occurredAt(),
                1
            );
            if (achievements != null) {
                achievements.recordMetricProgress(
                    factId,
                    event.learnerId(),
                    AchievementService.METRIC_TOEIC_DRILL_COMPLETED,
                    1
                );
            }
        }
        recordStreak(factId, event.learnerId(), event.occurredAt());
    }

    @ApplicationModuleListener
    public void grammarSession(GrammarSessionCompletedEvent event) {
        UUID factId = event.sessionId() != null
            ? event.sessionId()
            : deterministicFactId("grammar-session:" + event.learnerId() + ":" + event.catalogId() + ":" + event.occurredAt());
        recordStreak(factId, event.learnerId(), event.occurredAt());
    }

    @ApplicationModuleListener
    public void vocabularyUnit(VocabularyUnitCompletedEvent event) {
        UUID factId = event.sessionId() != null
            ? event.sessionId()
            : deterministicFactId("vocab-unit:" + event.learnerId() + ":" + event.unitId() + ":" + event.occurredAt());
        recordStreak(factId, event.learnerId(), event.occurredAt());
    }

    private void recordStreak(UUID factId, UUID learnerId, Instant occurredAt) {
        if (streaks == null) {
            return;
        }
        LearnerStreak streak = streaks.recordActivity(factId, learnerId, occurredAt);
        if (achievements != null && streak != null) {
            achievements.evaluateStreak(
                learnerId,
                Math.max(streak.currentStreak(), streak.longestStreak())
            );
        }
    }

    private static UUID grammarFactId(GrammarQuestionAnsweredEvent event) {
        if (event.attemptId() != null && event.itemId() != null) {
            return deterministicFactId("grammar:" + event.attemptId() + ":" + event.itemId());
        }
        if (event.attemptId() != null) {
            return event.attemptId();
        }
        return deterministicFactId("grammar:" + event.learnerId() + ":" + event.itemId() + ":" + event.occurredAt());
    }

    private static UUID deterministicFactId(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
