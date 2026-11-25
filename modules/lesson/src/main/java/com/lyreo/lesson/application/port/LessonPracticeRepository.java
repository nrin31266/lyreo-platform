package com.lyreo.lesson.application.port;

import java.util.Optional;
import java.util.UUID;

/** Persistence port for learner attempts/progress owned by the Lesson module. */
public interface LessonPracticeRepository {

    Optional<DictationTarget> findDictationTarget(
        UUID lessonId,
        UUID activityId,
        UUID sentenceId
    );

    default boolean isLessonAvailable(UUID lessonId) {
        return false;
    }

    default Optional<ShadowingTarget> findShadowingTarget(
        UUID lessonId,
        UUID activityId,
        UUID sentenceId
    ) {
        return Optional.empty();
    }

    UUID appendDictationAttempt(
        UUID learnerId,
        DictationTarget target,
        String answer,
        int serverScore,
        String detailJson
    );

    default void appendShadowingAttempt(
        UUID attemptId,
        UUID learnerId,
        UUID lessonId,
        UUID activityId,
        UUID sentenceId,
        int serverScore,
        String transcript,
        String detailJson
    ) {
        throw new UnsupportedOperationException("appendShadowingAttempt is not implemented");
    }

    default Optional<WatchTarget> findWatchTarget(UUID lessonId, UUID activityId) {
        return Optional.empty();
    }

    default WatchCompletion completeWatchActivity(UUID learnerId, UUID lessonId, UUID activityId, int totalItems) {
        throw new UnsupportedOperationException("completeWatchActivity is not implemented");
    }

    ActivityProgress refreshActivityProgress(
        UUID learnerId,
        UUID lessonId,
        UUID activityId,
        int completionScore
    );

    boolean tryMarkActivityCompleted(UUID learnerId, UUID activityId);

    boolean tryMarkLessonCompleted(UUID learnerId, UUID lessonId);

    record DictationTarget(
        UUID lessonId,
        UUID activityId,
        UUID sentenceId,
        String expectedText
    ) {}

    record ShadowingTarget(
        UUID lessonId,
        UUID activityId,
        UUID sentenceId,
        String expectedText,
        Long audioStartMs,
        Long audioEndMs,
        boolean playable
    ) {}

    record WatchTarget(
        UUID lessonId,
        UUID activityId,
        String activityType,
        int totalItems
    ) {}

    record WatchCompletion(
        int completedItems,
        int totalItems,
        boolean newlyCompleted
    ) {}

    record ActivityProgress(int completedItems, int totalItems, Integer bestScore) {}
}
