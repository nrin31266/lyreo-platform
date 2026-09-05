package com.lyreo.lesson.application;

import java.util.Optional;
import java.util.UUID;

/** Persistence port for learner attempts/progress owned by the Lesson module. */
public interface LessonPracticeRepository {

    Optional<DictationTarget> findDictationTarget(
        UUID lessonId,
        UUID activityId,
        UUID sentenceId
    );

    UUID appendDictationAttempt(
        UUID learnerId,
        DictationTarget target,
        String answer,
        int serverScore,
        String detailJson
    );

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

    record ActivityProgress(int completedItems, int totalItems, Integer bestScore) {}
}
