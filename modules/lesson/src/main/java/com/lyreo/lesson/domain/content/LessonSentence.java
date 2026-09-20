package com.lyreo.lesson.domain.content;

import java.util.UUID;

public record LessonSentence(
    UUID id,
    UUID lessonId,
    int position,
    String text,
    Long audioStartMs,
    Long audioEndMs,
    String optionalAudioClipObjectKey
) {}
