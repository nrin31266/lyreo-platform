package com.lyreo.curriculum.domain;

import java.util.Map;
import java.util.UUID;

public record CurriculumItem(
    UUID id,
    UUID sectionId,
    int position,
    ContentType contentType,
    UUID contentReferenceId,
    boolean required,
    String unlockRule,
    Map<String, Object> config
) {
    public CurriculumItem(
        UUID id,
        UUID sectionId,
        int position,
        ContentType contentType,
        UUID contentReferenceId,
        boolean required,
        String unlockRule
    ) {
        this(id, sectionId, position, contentType, contentReferenceId, required, unlockRule, Map.of());
    }

    public enum ContentType {
        LESSON,
        GRAMMAR_PRACTICE,
        TOEIC_DRILL,
        TOEIC_TEST,
        VOCABULARY_REVIEW
    }
}
