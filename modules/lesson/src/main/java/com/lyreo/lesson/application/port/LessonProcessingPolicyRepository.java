package com.lyreo.lesson.application.port;

import com.lyreo.lesson.domain.build.LessonProcessingPolicy;

/** Port so Lesson application does not depend on the technical JSON config registry. */
public interface LessonProcessingPolicyRepository {
    LessonProcessingPolicy current();
}
