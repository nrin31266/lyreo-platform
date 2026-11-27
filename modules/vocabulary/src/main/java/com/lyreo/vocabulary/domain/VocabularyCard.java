package com.lyreo.vocabulary.domain;

import java.time.Instant;
import java.util.UUID;

public record VocabularyCard(
    UUID id,
    UUID learnerId,
    UUID headwordId,
    String dedupKey,
    String term,
    String partOfSpeech,
    String meaningVi,
    String definitionEn,
    String ipa,
    String contextSentence,
    String contextTranslationVi,
    String sourceContextType,
    UUID sourceContextId,
    UUID sourceEntryId,
    Integer senseIndex,
    Long sourceRevision,
    UUID audioAssetId,
    UUID imageAssetId,
    String snapshotJson,
    Instant nextReviewAt,
    double stability,
    double difficulty,
    int lapseCount,
    int reviewCount
) {
    /** Backward-compatible 10-argument constructor used by starter tests and wire mocks. */
    public VocabularyCard(
        UUID id,
        UUID learnerId,
        UUID headwordId,
        String sourceContextType,
        UUID sourceContextId,
        Instant nextReviewAt,
        double stability,
        double difficulty,
        int lapseCount,
        int reviewCount
    ) {
        this(
            id,
            learnerId,
            headwordId,
            headwordId != null ? "LEXICON_HEADWORD:" + headwordId : null,
            "unknown",
            null,
            null,
            null,
            null,
            null,
            null,
            sourceContextType,
            sourceContextId,
            null,
            null,
            null,
            null,
            null,
            "{}",
            nextReviewAt,
            stability,
            difficulty,
            lapseCount,
            reviewCount
        );
    }
}
