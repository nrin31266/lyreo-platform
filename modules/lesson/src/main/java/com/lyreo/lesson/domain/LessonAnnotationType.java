package com.lyreo.lesson.domain;

/**
 * Optional learner-facing annotations attached to shared lesson content.
 * They are support data, not dedicated practice modes.
 */
public enum LessonAnnotationType {
    TRANSLATION,
    LEXICAL,
    GRAMMAR,
    ENTITY_HINTS,
    DICTATION_HINTS,
    SENTENCE_IPA,
    THOUGHT_GROUPS,
    LEARNING_TIPS
}
