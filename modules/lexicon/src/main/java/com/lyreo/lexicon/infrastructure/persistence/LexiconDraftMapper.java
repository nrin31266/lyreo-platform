package com.lyreo.lexicon.infrastructure.persistence;

import com.lyreo.lexicon.domain.LexiconDraft;
import com.lyreo.lexicon.domain.LexiconEntry;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.type.TypeReference;

public final class LexiconDraftMapper {
    private final ObjectMapper json;
    public LexiconDraftMapper(ObjectMapper json) {this.json=json;}
    private List<String> strings(String value) {return value==null?List.of():json.readValue(value,new TypeReference<>(){});}
    public LexiconDraft from(LexiconEntry e) {
        return new LexiconDraft(e.canonicalForm(),e.type().name(),e.headwordId(),e.sourceMetadataJson(),e.licenseText(),
            e.items().stream().map(i->new LexiconDraft.Item(i.id(),i.partOfSpeech(),i.posTitle(),i.etymologyText(),i.etymologyNumber())).toList(),
            e.senses().stream().map(s->new LexiconDraft.Sense(s.id(),s.itemId(),s.definitionEn(),s.translationVi(),s.translationStatus().name(),
                strings(s.rawGlossesJson()),s.examplesJson()==null?List.of():json.readValue(s.examplesJson(),new TypeReference<List<Map<String,Object>>>(){}),strings(s.tagsJson()),s.matchedQualifier(),s.definitions(),s.sourceContext())).toList(),
            e.forms().stream().map(f->new LexiconDraft.Form(f.id(),f.itemId(),f.form(),strings(f.tagsJson()))).toList(),
            e.pronunciations().stream().map(p->new LexiconDraft.Sound(p.id(),p.itemId(),p.accent(),p.ipa(),p.audioUrl(),p.assetId(),strings(p.tagsJson()),p.audioFile(),p.sourceUrl(),p.cachedAudioObjectKey(),p.pronunciationContext())).toList(),
            e.translations().stream().map(t->new LexiconDraft.Translation(t.id(),t.itemId(),t.senseId(),t.wordVi(),t.source(),t.sourceScope(),t.sourceSenseQualifier(),t.linkStatus(),t.sourcePos(),t.unlinkedReason(),strings(t.tagsJson()),t.sourceContext(),new LexiconDraft.TranslationOrigin(t.itemId(),t.senseId(),t.linkStatus()))).toList());
    }
    public LexiconEntry entry(UUID id,LexiconDraft d) {
        var pos=new HashMap<UUID,String>();d.items().forEach(i->pos.put(i.id(),i.partOfSpeech()));
        return new LexiconEntry(id,d.headwordId(),d.title(),com.lyreo.lexicon.application.LexiconLookupNormalizer.lookupQuery(d.title()),LexiconEntry.EntryType.valueOf(d.type()),"en",d.sourceMetadataJson(),d.licenseText(),
            d.items().stream().map(i->new LexiconEntry.LexiconItem(i.id(),i.partOfSpeech(),i.posTitle(),i.etymologyNumber(),i.etymologyText(),d.items().indexOf(i))).toList(),
            d.forms().stream().map(f->new LexiconEntry.LexiconForm(f.id(),f.itemId(),f.form(),com.lyreo.lexicon.application.LexiconLookupNormalizer.lookupQuery(f.form()),json.writeValueAsString(f.tags()))).toList(),
            d.senses().stream().map(s->new LexiconEntry.LexiconSense(s.id(),s.itemId(),d.senses().indexOf(s),pos.get(s.itemId()),s.definitionEn(),s.translationVi(),LexiconEntry.TranslationStatus.valueOf(s.translationStatus()),json.writeValueAsString(s.rawGlosses()),json.writeValueAsString(s.examples()),json.writeValueAsString(s.tags()),s.matchedQualifier(),s.definitions(),s.sourceContext())).toList(),
            d.pronunciations().stream().map(p->new LexiconEntry.Pronunciation(p.id(),p.itemId(),p.accent(),p.ipa(),p.audioFile(),p.audioUrl(),p.sourceUrl(),p.cachedAudioObjectKey(),json.writeValueAsString(p.tags()),p.assetId(),p.pronunciationContext())).toList(),
            d.translations().stream().map(t->new LexiconEntry.LexiconTranslation(t.id(),t.itemId(),t.senseId(),t.wordVi(),t.source(),t.sourceScope(),t.sourceSenseQualifier(),t.linkStatus(),t.unlinkedReason(),json.writeValueAsString(t.tags()==null?List.of():t.tags()),t.sourcePos(),t.sourceContext())).toList());
    }
}
