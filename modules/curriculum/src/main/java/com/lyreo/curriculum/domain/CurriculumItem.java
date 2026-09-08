package com.lyreo.curriculum.domain;

import java.util.UUID;

public record CurriculumItem(
    UUID id,
    UUID sectionId,
    int position,
    ContentType contentType,
    UUID contentReferenceId,
    boolean required,
    String unlockRule
) {
    public enum ContentType {
        LESSON,
        GRAMMAR_PRACTICE,
        TOEIC_DRILL,
        TOEIC_TEST,
        VOCABULARY_REVIEW,
        SPEAKING_SCENARIO
    }
}
