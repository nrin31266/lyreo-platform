package com.lyreo.lesson.application;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Query-side projection used by Admin preview without leaking persistence entities. */
public interface LessonPreviewQuery {
    Optional<Map<String, Object>> find(UUID lessonId);
}
