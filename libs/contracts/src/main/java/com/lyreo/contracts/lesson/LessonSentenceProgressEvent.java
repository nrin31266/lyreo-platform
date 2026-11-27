package com.lyreo.contracts.lesson;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Published when a learner passes an individual sentence in a Dictation or Shadowing activity.
 * Carries the learner's current passing sentence IDs for that activity so Curriculum slice items
 * can progress without waiting for a first-time full-activity completion transition.
 */
public record LessonSentenceProgressEvent(
    UUID factId,
    UUID learnerId,
    UUID lessonId,
    UUID activityId,
    String activityType,
    UUID sentenceId,
    int serverScore,
    List<UUID> completedSentenceIds,
    Instant occurredAt
) {
    public LessonSentenceProgressEvent {
        completedSentenceIds = completedSentenceIds == null ? List.of() : List.copyOf(completedSentenceIds);
    }
}
