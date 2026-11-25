package com.lyreo.lesson.api;

import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * Public Lesson boundary port allowing Speech Assessment to record scored
 * learner Shadowing attempts and advance lesson completion without cyclic dependencies.
 */
@NamedInterface(value = "shadowing", propagate = false)
public interface LearnerShadowingCompletionPort {

    ShadowingCompletionResult recordScoredAttempt(
        UUID learnerId,
        UUID lessonId,
        UUID activityId,
        UUID sentenceId,
        UUID attemptId,
        int serverScore,
        String transcript,
        String detailJson
    );

    @NamedInterface(value = "shadowing", propagate = false)
    record ShadowingCompletionResult(
        UUID attemptId,
        int serverScore,
        int completedItems,
        int totalItems,
        boolean activityCompleted,
        boolean lessonCompleted
    ) {}
}
