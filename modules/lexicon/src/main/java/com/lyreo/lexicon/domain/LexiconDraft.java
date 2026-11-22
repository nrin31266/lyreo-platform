package com.lyreo.lexicon.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record LexiconDraft(String title, String type, UUID headwordId, String sourceMetadataJson,
    String licenseText, List<Item> items, List<Sense> senses, List<Form> forms,
    List<Sound> pronunciations, List<Translation> translations) {
    public record Item(UUID id, String partOfSpeech, String posTitle, String etymologyText, Integer etymologyNumber) {
        public Item(UUID id,String partOfSpeech,String posTitle,String etymologyText) {this(id,partOfSpeech,posTitle,etymologyText,null);}
    }
    public record Sense(UUID id, UUID itemId, String definitionEn, String translationVi,
        String translationStatus, List<String> rawGlosses, List<Map<String,Object>> examples,
        List<String> tags, String matchedQualifier,Map<String,String> definitions,Map<String,Object> sourceContext) {
        public Sense(UUID id,UUID itemId,String definitionEn,String translationVi,String translationStatus,List<String> rawGlosses,List<Map<String,Object>> examples,List<String> tags,String matchedQualifier) {
            this(id,itemId,definitionEn,translationVi,translationStatus,rawGlosses,examples,tags,matchedQualifier,Map.of(),Map.of());
        }
    }
    public record Form(UUID id, UUID itemId, String form, List<String> tags) {}
    public record Sound(UUID id, UUID itemId, String accent, String ipa, String audioUrl, UUID assetId, List<String> tags,
                        String audioFile,String sourceUrl,String cachedAudioObjectKey,Map<String,Object> pronunciationContext) {
        public Sound(UUID id,UUID itemId,String accent,String ipa,String audioUrl,UUID assetId,List<String> tags,String audioFile,String sourceUrl,String cachedAudioObjectKey) {
            this(id,itemId,accent,ipa,audioUrl,assetId,tags,audioFile,sourceUrl,cachedAudioObjectKey,Map.of());
        }
        public Sound(UUID id,UUID itemId,String accent,String ipa,String audioUrl,UUID assetId,List<String> tags) {
            this(id,itemId,accent,ipa,audioUrl,assetId,tags,null,null,null,Map.of());
        }
    }
    public record TranslationOrigin(UUID itemId,UUID senseId,String linkStatus) {}
    public record Translation(UUID id, UUID itemId, UUID senseId, String wordVi, String source,
        String sourceScope, String sourceSenseQualifier, String linkStatus, String sourcePos,
        String unlinkedReason,List<String> tags,Map<String,Object> sourceContext,TranslationOrigin origin) {
        public Translation(UUID id,UUID itemId,UUID senseId,String wordVi,String source,String sourceScope,String sourceSenseQualifier,String linkStatus,String sourcePos) {
            this(id,itemId,senseId,wordVi,source,sourceScope,sourceSenseQualifier,linkStatus,sourcePos,null,null,null,null);
        }
    }

    private static Map<String,Object> editedPronunciation(Sound value,Sound source) {
        var context=new java.util.HashMap<String,Object>(source.pronunciationContext==null?Map.of():source.pronunciationContext);
        var supplied=value.pronunciationContext==null?Map.<String,Object>of():value.pronunciationContext;
        for(String field:List.of("form","usage","notation_system")) if(supplied.containsKey(field)) context.put(field,supplied.get(field));
        if(!java.util.Objects.equals(value.accent,source.accent)) {
            var regions=java.util.Arrays.stream(java.util.Objects.toString(value.accent,"").split("/"))
                .filter(region->java.util.Set.of("US","UK","AU","NZ","CA","IE").contains(region)).toList();
            context.put("regions",regions);
        }
        if(!java.util.Objects.equals(value.ipa,source.ipa)) {
            context.put("notation",java.util.Objects.toString(value.ipa,""));
            if(!supplied.containsKey("notation_system")) context.put("notation_system","SOURCE");
        }
        if(!java.util.Objects.equals(context.get("usage"),source.pronunciationContext==null?null:source.pronunciationContext.get("usage")) || !java.util.Objects.equals(value.itemId,source.itemId))
            context.put("scope_status", "EXPLICIT");
        return context;
    }

    /** Recover newly exposed provenance from matching source identities, including older sparse drafts. */
    public LexiconDraft withProvenanceFrom(LexiconDraft reference) {
        var oldItems=reference.items.stream().collect(java.util.stream.Collectors.toMap(Item::id,i->i));
        var oldSounds=reference.pronunciations.stream().collect(java.util.stream.Collectors.toMap(Sound::id,s->s));
        var oldTranslations=reference.translations.stream().collect(java.util.stream.Collectors.toMap(Translation::id,t->t));
        var oldSenses=reference.senses.stream().collect(java.util.stream.Collectors.toMap(Sense::id,s->s));
        var enrichedSenses=senses.stream().map(s->{var old=oldSenses.get(s.id);var content=new java.util.HashMap<String,String>(s.definitions==null?Map.of():s.definitions);
            if(s.definitionEn!=null && !s.definitionEn.isBlank()) content.put("en",s.definitionEn);else content.remove("en");
            if(s.translationVi!=null && !s.translationVi.isBlank()) content.put("vi",s.translationVi);else content.remove("vi");
            return new Sense(s.id,s.itemId,s.definitionEn,s.translationVi,s.translationStatus,s.rawGlosses,s.examples,s.tags,s.matchedQualifier,content,old==null?s.sourceContext:old.sourceContext);}).toList();
        return new LexiconDraft(title,type,headwordId,sourceMetadataJson,licenseText,
            items.stream().map(i->{var old=oldItems.get(i.id);return old==null?i:new Item(i.id,i.partOfSpeech,i.posTitle,i.etymologyText,old.etymologyNumber);}).toList(),enrichedSenses,forms,
            pronunciations.stream().map(s->{var old=oldSounds.get(s.id);if(old==null) old=new Sound(s.id,null,null,null,null,null,List.of(),null,null,null,Map.of("source","admin","form",title,"scope_status","UNSPECIFIED","notation_system","SOURCE"));return new Sound(s.id,s.itemId,s.accent,s.ipa,s.audioUrl,s.assetId,s.tags,old.audioFile,old.sourceUrl,old.cachedAudioObjectKey,editedPronunciation(s,old));}).toList(),
            translations.stream().map(t->{var old=oldTranslations.get(t.id);return old==null?t:new Translation(t.id,t.itemId,t.senseId,t.wordVi,old.source,old.sourceScope,old.sourceSenseQualifier,t.linkStatus,old.sourcePos,old.unlinkedReason,old.tags,old.sourceContext,old.origin==null?new TranslationOrigin(old.itemId,old.senseId,old.linkStatus):old.origin);}).toList());
    }
}
