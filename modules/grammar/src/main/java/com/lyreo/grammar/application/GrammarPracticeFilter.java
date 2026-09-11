package com.lyreo.grammar.application;

import java.util.UUID;

/**
 * Filter parameters for grammar practice question discovery.
 * Owned by the application layer; the repository accepts it as a persistence port input.
 */
public record GrammarPracticeFilter(
    UUID topicId,
    UUID subtopicId,
    UUID bankSetId,
    Integer difficultyLevel
) {}
