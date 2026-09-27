package com.lyreo.lexicon.domain;

import java.util.List;
import java.util.UUID;

public record LexiconEntry(
    UUID id,
    UUID headwordId,
    String canonicalForm,
    String normalizedForm,
    EntryType type,
    String language,
    String sourceMetadataJson,
    String licenseText,
    List<LexiconItem> items,
    List<LexiconForm> forms,
    List<LexiconSense> senses,
    List<Pronunciation> pronunciations,
    List<LexiconTranslation> translations
) {
    public enum EntryType { WORD, PHRASE, PHRASAL_VERB, IDIOM, COLLOCATION }

    public record LexiconItem(UUID id, String partOfSpeech, String posTitle,
                              Integer etymologyNumber, String etymologyText, int orderIndex) {}

    public record LexiconForm(UUID id, UUID itemId, String form, String normalizedForm, String tagsJson) {}

    public record LexiconSense(
        UUID id,
        UUID itemId,
        int position,
        String partOfSpeech,
        String definitionEn,
        String translationVi,
        TranslationStatus translationStatus,
        String rawGlossesJson,
        String examplesJson,
        String tagsJson,
        String matchedQualifier
    ) {}

    public enum TranslationStatus {
        AVAILABLE, MISSING, NEEDS_REVIEW, AI_GENERATED, VERIFIED
    }

    public record Pronunciation(
        UUID id,
        UUID itemId,
        String accent,
        String ipa,
        String audioFile,
        String audioUrl,
        String sourceUrl,
        String cachedAudioObjectKey,
        String tagsJson
    ) {}

    public record LexiconTranslation(UUID id, UUID itemId, UUID senseId, String wordVi,
                                     String source, String sourceScope, String sourceSenseQualifier,
                                     String linkStatus, String unlinkedReason, String tagsJson) {}
}
