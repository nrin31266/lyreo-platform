package com.lyreo.grammar.domain;

import java.util.List;
import java.util.UUID;

/** Canonical imported Grammar Bank question. The answer key never leaves pre-submit APIs. */
public record GrammarQuestion(
    UUID id,
    String questionText,
    List<Option> options,
    String correctAnswer,
    String explanationVi,
    String translationVi,
    String answerTranslationVi,
    String vocabularyNote,
    int difficultyLevel,
    UUID topicId,
    UUID subtopicId,
    ExplanationPolicy explanationPolicy
) {
    public record Option(String key, String text) {}

    public enum ExplanationPolicy {
        SOURCE,
        AI_FALLBACK,
        AI_PREFERRED
    }
}
