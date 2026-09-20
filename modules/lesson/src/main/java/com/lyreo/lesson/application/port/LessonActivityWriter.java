package com.lyreo.lesson.application.port;

import com.lyreo.lesson.domain.content.LessonActivityType;
import java.util.Set;
import java.util.UUID;

/** Output port for durable lesson activity definitions. */
public interface LessonActivityWriter {
    void replace(UUID lessonId, Set<LessonActivityType> activityTypes);
}
