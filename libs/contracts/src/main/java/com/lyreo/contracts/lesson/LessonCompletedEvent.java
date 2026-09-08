package com.lyreo.contracts.lesson;

import java.time.Instant;
import java.util.UUID;

public record LessonCompletedEvent(UUID learnerId, UUID lessonId, Instant occurredAt) {}
