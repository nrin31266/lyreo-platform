package com.lyreo.lesson.domain;

import java.util.Locale;
import java.util.Set;

/**
 * Admin-controlled capability envelope for Lesson Builder.
 *
 * <p>The creator still chooses options for one lesson, but those options must stay inside
 * this server-owned policy. This is the concrete implementation of the configuration precedence:
 * admin policy -> lesson build snapshot.</p>
 */
public record LessonProcessingPolicy(
    Set<LessonActivityType> allowedActivities,
    Set<LessonAnnotationType> allowedAnnotations,
    Set<String> allowedPronunciationStrategies,
    Set<String> allowedAccents
) {
    public LessonProcessingPolicy {
        allowedActivities = allowedActivities == null ? Set.of() : Set.copyOf(allowedActivities);
        allowedAnnotations = allowedAnnotations == null ? Set.of() : Set.copyOf(allowedAnnotations);
        allowedPronunciationStrategies = normalize(allowedPronunciationStrategies);
        allowedAccents = normalize(allowedAccents);
    }

    public void validate(LessonBuildOptions options) {
        if (!allowedActivities.containsAll(options.activities())) {
            throw new IllegalArgumentException("Lesson requests an activity disabled by admin policy");
        }
        if (!allowedAnnotations.containsAll(options.annotations())) {
            throw new IllegalArgumentException("Lesson requests an annotation disabled by admin policy");
        }
        if (!allowedPronunciationStrategies.contains(options.pronunciationStrategy())) {
            throw new IllegalArgumentException("Pronunciation strategy disabled by admin policy: " + options.pronunciationStrategy());
        }
        if (!allowedAccents.contains(options.accent())) {
            throw new IllegalArgumentException("Accent disabled by admin policy: " + options.accent());
        }
    }

    public static LessonProcessingPolicy defaults() {
        return new LessonProcessingPolicy(
            Set.of(LessonActivityType.values()),
            Set.of(LessonAnnotationType.values()),
            Set.of("DISABLED", "ON_DEMAND", "PREGENERATE"),
            Set.of("US", "UK")
        );
    }

    private static Set<String> normalize(Set<String> values) {
        if (values == null) return Set.of();
        return values.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(value -> value.trim().toUpperCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
