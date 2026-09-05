package com.lyreo.lesson.domain;

import java.time.Instant;
import java.util.UUID;

public record Lesson(
    UUID id,
    String title,
    LessonSourceType sourceType,
    String sourceText,
    String sourceReference,
    String canonicalAudioObjectKey,
    String status,
    Instant createdAt
) {}
