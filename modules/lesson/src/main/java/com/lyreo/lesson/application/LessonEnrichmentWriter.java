package com.lyreo.lesson.application;

import com.lyreo.lesson.domain.LessonSentence;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Output port for normalized lesson annotations/timestamps.
 * Raw provider payloads belong in object storage, not in this API.
 */
public interface LessonEnrichmentWriter {
    void replaceAlignment(UUID lessonId, List<LessonSentence> sentences, Map<String, Object> output);

    void replaceNlpHints(
        UUID lessonId,
        List<LessonSentence> sentences,
        Map<String, Object> output,
        boolean entityHints,
        boolean dictationHints
    );

    void replaceStructuredAnnotations(
        UUID lessonId,
        String annotationType,
        List<LessonSentence> sentences,
        Object structured
    );

    void replaceSentenceIpa(
        UUID lessonId,
        List<LessonSentence> sentences,
        Object structured,
        String provider,
        String model,
        String accent
    );
}
