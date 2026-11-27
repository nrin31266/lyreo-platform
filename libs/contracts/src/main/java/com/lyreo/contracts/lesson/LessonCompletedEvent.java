package com.lyreo.contracts.lesson;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

public record LessonCompletedEvent(
    UUID factId,
    UUID learnerId,
    UUID lessonId,
    Instant occurredAt
) {
    public LessonCompletedEvent(UUID learnerId, UUID lessonId, Instant occurredAt) {
        this(
            UUID.nameUUIDFromBytes(("lesson:" + learnerId + ":" + lessonId).getBytes(StandardCharsets.UTF_8)),
            learnerId,
            lessonId,
            occurredAt
        );
    }
}
