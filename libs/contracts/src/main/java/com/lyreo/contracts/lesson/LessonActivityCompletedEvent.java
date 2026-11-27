package com.lyreo.contracts.lesson;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LessonActivityCompletedEvent(
    UUID factId,
    UUID learnerId,
    UUID lessonId,
    UUID activityId,
    String activityType,
    int serverScore,
    Instant occurredAt,
    List<UUID> completedSentenceIds
) {
    public LessonActivityCompletedEvent {
        completedSentenceIds = completedSentenceIds == null ? List.of() : List.copyOf(completedSentenceIds);
    }

    public LessonActivityCompletedEvent(
        UUID factId,
        UUID learnerId,
        UUID lessonId,
        UUID activityId,
        String activityType,
        int serverScore,
        Instant occurredAt
    ) {
        this(factId, learnerId, lessonId, activityId, activityType, serverScore, occurredAt, List.of());
    }

    public LessonActivityCompletedEvent(
        UUID learnerId,
        UUID lessonId,
        UUID activityId,
        String activityType,
        int serverScore,
        Instant occurredAt
    ) {
        this(
            UUID.nameUUIDFromBytes(("lesson-activity:" + learnerId + ":" + activityId).getBytes(StandardCharsets.UTF_8)),
            learnerId,
            lessonId,
            activityId,
            activityType,
            serverScore,
            occurredAt,
            List.of()
        );
    }
}
