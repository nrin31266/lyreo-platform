package com.lyreo.grammar.application;

import java.util.UUID;

/**
 * Filter parameters for grammar practice question discovery.
 * Owned by the application layer; the repository accepts it as a persistence port input.
 */
public record GrammarPracticeFilter(
    UUID topicCatalogId,
    UUID subtopicCatalogId,
    UUID bankCatalogId,
    Integer difficultyLevel
) {
    public boolean hasCatalogFilter() {
        return topicCatalogId != null || subtopicCatalogId != null || bankCatalogId != null;
    }
}
