package com.lyreo.lesson.application;

import com.lyreo.lesson.domain.LessonProcessingPolicy;

/** Port so Lesson application does not depend on the technical JSON config registry. */
public interface LessonProcessingPolicyRepository {
    LessonProcessingPolicy current();
}
