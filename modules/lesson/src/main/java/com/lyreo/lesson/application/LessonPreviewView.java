package com.lyreo.lesson.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Typed application projection for lesson preview. */
public record LessonPreviewView(
    UUID id,
    String title,
    String sourceType,
    String sourceText,
    String sourceReference,
    String canonicalAudioObjectKey,
    String status,
    Instant createdAt,
    Instant updatedAt,
    List<SentenceView> sentences,
    List<ActivityView> activities,
    List<BuildJobView> buildJobs
) {
    public record SentenceView(
        UUID id,
        int position,
        String text,
        Integer audioStartMs,
        Integer audioEndMs,
        String audioClipObjectKey,
        List<WordView> words,
        List<AnnotationView> annotations
    ) {}

    public record WordView(
        int position,
        String surfaceText,
        Integer startMs,
        Integer endMs
    ) {}

    public record AnnotationView(
        String annotationType,
        Object payload,
        String generatedBy,
        String provider,
        String model,
        String status,
        Instant createdAt
    ) {}

    public record ActivityView(
        UUID id,
        String activityType,
        int position,
        boolean enabled,
        String configJson
    ) {}

    public record BuildJobView(
        UUID jobId,
        Instant createdAt,
        String status,
        String currentStep,
        Integer progressPercent,
        Integer attemptCount,
        String errorMessage
    ) {}
}
