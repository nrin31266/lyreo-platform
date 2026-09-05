package com.lyreo.contracts.lesson;

import java.time.Instant;
import java.util.UUID;

public record LessonActivityCompletedEvent(
    UUID learnerId, UUID lessonId, UUID activityId, String activityType,
    int serverScore, Instant occurredAt) {}
