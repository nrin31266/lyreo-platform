package com.lyreo.lexicon.domain;

import java.util.List;
import java.util.UUID;
import java.util.Map;

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
        String matchedQualifier, Map<String,String> definitions, Map<String,Object> sourceContext
    ) {
        public LexiconSense(UUID id,UUID itemId,int position,String partOfSpeech,String definitionEn,String translationVi,
            TranslationStatus translationStatus,String rawGlossesJson,String examplesJson,String tagsJson,String matchedQualifier) {
            this(id,itemId,position,partOfSpeech,definitionEn,translationVi,translationStatus,rawGlossesJson,examplesJson,tagsJson,matchedQualifier,
                definitionEn==null?Map.of():Map.of("en",definitionEn),Map.of());
        }
    }

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
        String tagsJson,
        UUID assetId, Map<String,Object> pronunciationContext
    ) {
        public Pronunciation(UUID id,UUID itemId,String accent,String ipa,String audioFile,String audioUrl,
            String sourceUrl,String cachedAudioObjectKey,String tagsJson,UUID assetId) {
            this(id,itemId,accent,ipa,audioFile,audioUrl,sourceUrl,cachedAudioObjectKey,tagsJson,assetId,Map.of());
        }
        public Pronunciation(UUID id,UUID itemId,String accent,String ipa,String audioFile,String audioUrl,
                             String sourceUrl,String cachedAudioObjectKey,String tagsJson) {
            this(id,itemId,accent,ipa,audioFile,audioUrl,sourceUrl,cachedAudioObjectKey,tagsJson,null,Map.of());
        }
    }

    public record LexiconTranslation(UUID id, UUID itemId, UUID senseId, String wordVi,
                                     String source, String sourceScope, String sourceSenseQualifier,
                                     String linkStatus, String unlinkedReason, String tagsJson, String sourcePos,
                                     Map<String,Object> sourceContext) {
        public LexiconTranslation(UUID id, UUID itemId, UUID senseId, String wordVi, String source,
                                  String sourceScope, String sourceSenseQualifier, String linkStatus,
                                  String unlinkedReason, String tagsJson, String sourcePos) {
            this(id,itemId,senseId,wordVi,source,sourceScope,sourceSenseQualifier,linkStatus,unlinkedReason,tagsJson,sourcePos,null);
        }
    }
}
