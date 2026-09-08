package com.lyreo.lesson.domain;

import java.util.List;

public record LessonBuildPlan(
    LessonSourceType sourceType,
    List<LessonBuildStep> steps,
    LessonBuildOptions options
) {
    public boolean requires(LessonBuildStep step) {
        return steps.contains(step);
    }
}
