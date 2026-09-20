package com.lyreo.lesson.domain.build;

import com.lyreo.lesson.domain.content.LessonSourceType;
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
