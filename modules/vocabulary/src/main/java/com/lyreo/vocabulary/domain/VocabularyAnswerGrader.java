package com.lyreo.vocabulary.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/**
 * Normalizes and grades learner answers for all 6 vocabulary practice modes.
 * Text grading uses Unicode NFKC normalization, trimming, whitespace collapsing,
 * and locale-invariant case-insensitive comparison.
 */
public final class VocabularyAnswerGrader {

    /** Normalizes text using NFKC, trim, collapsed whitespace, and lowercase. */
    public String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        return nfkc.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Grades a single question answer against its locked server-side snapshot item. */
    public boolean isCorrect(VocabularyStudyModels.SnapshotQuestion question, String rawAnswer) {
        Objects.requireNonNull(question, "question");
        if (rawAnswer == null || rawAnswer.isBlank()) {
            return false;
        }
        return switch (question.mode()) {
            case CHOOSE_MEANING, CHOOSE_WORD -> normalize(rawAnswer).equals(normalize(question.correctAnswer()));
            case LETTER_SCRAMBLE, MISSING_LETTERS, LISTEN_WRITE, CONTEXT_CLOZE ->
                normalize(rawAnswer).equals(normalize(question.correctAnswer()));
        };
    }
}
