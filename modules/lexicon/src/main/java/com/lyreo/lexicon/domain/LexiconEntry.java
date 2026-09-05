package com.lyreo.lexicon.domain;

import java.util.List;
import java.util.UUID;

public record LexiconEntry(
    UUID id,
    String canonicalForm,
    String normalizedForm,
    EntryType type,
    String language,
    List<LexiconSense> senses,
    List<Pronunciation> pronunciations
) {
    public enum EntryType { WORD, PHRASE, PHRASAL_VERB, IDIOM, COLLOCATION }

    public record LexiconSense(
        UUID id,
        String partOfSpeech,
        String definitionEn,
        String translationVi,
        TranslationStatus translationStatus,
        UUID sourceId
    ) {}

    public enum TranslationStatus {
        AVAILABLE, MISSING, NEEDS_REVIEW, AI_GENERATED, VERIFIED
    }

    public record Pronunciation(
        String accent,
        String ipa,
        String externalAudioUrl,
        String cachedAudioObjectKey,
        UUID sourceId
    ) {}
}
