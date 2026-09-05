package com.lyreo.lesson.infrastructure;

import com.lyreo.lesson.application.LessonProcessingPolicyRepository;
import com.lyreo.lesson.domain.LessonProcessingPolicy;
import com.lyreo.platform.config.application.RuntimeConfigService;

/** Reads the typed Lesson processing policy from the platform runtime-config registry. */
public final class RuntimeLessonProcessingPolicyRepository implements LessonProcessingPolicyRepository {
    private static final String OWNER = "lesson";
    private static final String KEY = "processing-policy";

    private final RuntimeConfigService runtimeConfig;

    public RuntimeLessonProcessingPolicyRepository(RuntimeConfigService runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
    }

    @Override
    public LessonProcessingPolicy current() {
        return runtimeConfig.read(
            OWNER,
            KEY,
            LessonProcessingPolicy.class,
            LessonProcessingPolicy::defaults
        );
    }
}
