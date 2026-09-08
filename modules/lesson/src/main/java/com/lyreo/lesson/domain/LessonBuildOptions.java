package com.lyreo.lesson.domain;

import java.util.Locale;
import java.util.Set;

/** Immutable snapshot of creator choices used by LessonBuildPlanner. */
public record LessonBuildOptions(
    LessonSourceType sourceType,
    Set<LessonActivityType> activities,
    Set<LessonAnnotationType> annotations,
    String accent,
    String pronunciationStrategy
) {
    private static final Set<String> PRONUNCIATION_STRATEGIES = Set.of("DISABLED", "ON_DEMAND", "PREGENERATE");

    public LessonBuildOptions {
        if (sourceType == null) throw new IllegalArgumentException("sourceType is required");
        activities = activities == null ? Set.of() : Set.copyOf(activities);
        annotations = annotations == null ? Set.of() : Set.copyOf(annotations);
        accent = normalize(accent, "US");
        pronunciationStrategy = normalize(pronunciationStrategy, "ON_DEMAND");
        if (!PRONUNCIATION_STRATEGIES.contains(pronunciationStrategy)) {
            throw new IllegalArgumentException("Unsupported pronunciation strategy: " + pronunciationStrategy);
        }
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }
}
