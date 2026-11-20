package com.lyreo.lexicon.application;
import com.lyreo.lexicon.application.port.LexiconEditorSource;
import com.lyreo.lexicon.domain.LexiconDraft;
import com.lyreo.lexicon.domain.LexiconEntry;
import com.lyreo.platform.content.DocumentStore;
import com.lyreo.platform.content.AssetStore;
import com.lyreo.platform.content.DocumentStore.Document;
import com.lyreo.contracts.errors.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LexiconEditor {
    private final DocumentStore documents;
    private final LexiconEditorSource source;
    private final AssetStore assets;
    public LexiconEditor(DocumentStore documents,LexiconEditorSource source,AssetStore assets) {this.documents=documents;this.source=source;this.assets=assets;}
    public Document<LexiconDraft> get(UUID id) {
        var row=documents.find("lexicon",id,LexiconDraft.class);
        if(row.isPresent() && documents.sourceSelected("lexicon",row.get().id())) {
            var current=row.get();
            return new Document<>(current.id(),current.revision(),"PUBLISHED",null,source.source(current.id()).orElseThrow(()->new ResourceNotFoundException("Source entry unavailable")),current.updatedAt(),current.updatedBy(),false);
        }
        if(row.isPresent()) {
            var current=row.get();
            return source.source(current.id()).map(raw->{
                var enriched=new Document<>(current.id(),current.revision(),current.status(),current.publicationId(),current.draft().withProvenanceFrom(raw),current.updatedAt(),current.updatedBy(),current.hasUnpublishedChanges());
                return documents.againstSource(enriched,raw,"PUBLISHED");
            }).orElse(current);
        }
        return row.orElseGet(()->documents.source(id,source.source(id).orElseThrow(()->new ResourceNotFoundException("Entry not found"))));
    }
    public boolean sourcePublished(UUID id) {return source.source(id).isPresent();}
    public List<LexiconEditorSource.Summary> list(String q,String type,String translation,String state,String pos,String after,int size) {if(q.length()>500 || size<1 || size>100) throw new RequestValidationException("Invalid search or page size");return source.list(q,type,translation,state,pos,after,size);}
    public long count(String q,String type,String translation,String state,String pos) {
        if(q.length()>500) throw new RequestValidationException("Invalid search");
        return source.count(q,type,translation,state,pos);
    }
    public List<LexiconEditorSource.Summary> browse(String q,String type,String translation,String state,String pos,String after,String before,boolean tail,int skip,int size) {
        if(q.length()>500 || size<1 || size>100 || skip<0 || skip>90 || (after!=null && before!=null) || (tail && (after!=null || before!=null))) throw new RequestValidationException("Invalid cursor or page size");
        return source.browse(q,type,translation,state,pos,after,before,tail,skip,size);
    }
    public Document<LexiconDraft> save(UUID id,long revision,LexiconDraft data,String actor) {
        validate(data,false);
        var current=get(id);if(!Objects.equals(current.draft().headwordId(),data.headwordId()) || !current.draft().title().equals(data.title())) throw new RequestValidationException("Headword identity cannot change");
        if(!Objects.equals(current.draft().sourceMetadataJson(),data.sourceMetadataJson()) || !Objects.equals(current.draft().licenseText(),data.licenseText())) throw new RequestValidationException("Keep the original source attribution and license");
        data=data.withProvenanceFrom(current.draft());validate(data,false);
        documents.save("lexicon",id,revision,data,actor,LexiconDraft.class);return get(id);
    }
    @Transactional public Document<LexiconDraft> publish(UUID id,long revision,String reason,String actor) {validatePublication(id,get(id).draft());var published=documents.publish("lexicon",id,revision,reason,actor,LexiconDraft.class);source.indexPublication(id,published.draft());return published;}
    @Transactional public Document<LexiconDraft> restore(UUID id,long revision,UUID publicationId,String actor) {var previous=documents.publication("lexicon",id,publicationId,LexiconDraft.class);validatePublication(id,previous.content());var restored=documents.restore("lexicon",id,revision,publicationId,actor,LexiconDraft.class);source.indexPublication(id,restored.draft());return restored;}
    private void validatePublication(UUID id, LexiconDraft content) {
        var sourceStubs = content.senses().stream().anyMatch(sense -> !hasMeaning(sense))
            ? source.source(id).stream().flatMap(draft -> draft.senses().stream())
                .filter(sense -> !hasMeaning(sense) && sense.tags().contains("no-gloss")).toList()
            : List.<LexiconDraft.Sense>of();
        validate(content,true,sourceStubs);
        for(var pronunciation:content.pronunciations()) if(pronunciation.assetId()!=null) {
            if(!assets.get(pronunciation.assetId()).mimeType().startsWith("audio/") || !assets.exists(pronunciation.assetId()))
                throw new RequestValidationException("Pronunciation audio is unavailable or has the wrong type");
        }
    }
    public static void validate(LexiconDraft d,boolean publish) {
        validate(d,publish,List.of());
    }
    private static boolean hasMeaning(LexiconDraft.Sense sense) {
        return sense.definitionEn()!=null && !sense.definitionEn().isBlank()
            || sense.translationVi()!=null && !sense.translationVi().isBlank();
    }
    private static void validate(LexiconDraft d,boolean publish,List<LexiconDraft.Sense> sourceStubs) {
        require(d!=null && d.title()!=null && !d.title().isBlank() && d.title().length()<=500,"Headword is required (up to 500 characters)");
        require(Arrays.stream(LexiconEntry.EntryType.values()).anyMatch(t->t.name().equals(d.type())),"Invalid entry type");
        require(d.items()!=null && d.senses()!=null && d.forms()!=null && d.pronunciations()!=null && d.translations()!=null,"Entry collections are required");
        require(d.items().size()<=100 && d.senses().size()<=1000 && d.forms().size()<=5000 && d.pronunciations().size()<=500 && d.translations().size()<=5000,"Entry collections exceed supported limits");
        var ids=new HashSet<UUID>();
        for(var item:d.items()) require(item!=null && item.id()!=null && ids.add(item.id()) && item.partOfSpeech()!=null && !item.partOfSpeech().isBlank(),"Each item needs a unique ID and part of speech");
        var senses=new HashSet<UUID>();
        for(var s:d.senses()) {
            require(s!=null && s.id()!=null && senses.add(s.id()) && ids.contains(s.itemId()),"Sense references an invalid item");
            require(!publish || hasMeaning(s) || sourceStubs.contains(s),"A definition in at least one language is required for publication");
            require(s.rawGlosses()!=null && s.examples()!=null && s.tags()!=null,"Sense collections are required");
            require(Arrays.stream(LexiconEntry.TranslationStatus.values()).anyMatch(t->t.name().equals(s.translationStatus())),"Invalid translation status");
            require(!"VERIFIED".equals(s.translationStatus()) || s.translationVi()!=null && !s.translationVi().isBlank(),"Verified translation cannot be empty");
        }
        if(publish) require(d.senses().stream().anyMatch(LexiconEditor::hasMeaning),"Add at least one readable meaning before publication");
        var translationIds=new HashSet<UUID>();
        for(var t:d.translations()) {
            require(t!=null && t.id()!=null && translationIds.add(t.id()) && t.wordVi()!=null && t.linkStatus()!=null,"Invalid translation");
            require(t.senseId()==null || senses.contains(t.senseId()),"Translation references an invalid sense");
            require(t.itemId()==null || ids.contains(t.itemId()),"Translation references an invalid item");
            require(Set.of("DIRECT_SENSE","QUALIFIER_MATCH","ITEM_CANDIDATE","ENTRY_CANDIDATE","UNLINKED").contains(t.linkStatus()),"Invalid translation link status");
            require(!Set.of("DIRECT_SENSE","QUALIFIER_MATCH").contains(t.linkStatus()) || t.senseId()!=null,"Confirmed translation needs a sense");
            if(t.senseId()!=null) {
                var sense=d.senses().stream().filter(s->s.id().equals(t.senseId())).findFirst().orElseThrow();
                require(Objects.equals(t.itemId(),sense.itemId()),"Translation and sense must belong to the same item");
                require(t.origin()==null || t.origin().itemId()==null || t.origin().itemId().equals(t.itemId()),"An item-scoped source translation cannot be moved to another item");
            }
        }
        var formIds=new HashSet<UUID>();
        for(var f:d.forms()) require(f!=null && f.id()!=null && formIds.add(f.id()) && f.form()!=null && f.tags()!=null && (f.itemId()==null || ids.contains(f.itemId())),"Invalid or duplicate word form");
        var soundIds=new HashSet<UUID>();
        for(var p:d.pronunciations()) {
            require(p!=null && p.id()!=null && soundIds.add(p.id()) && p.tags()!=null && (p.itemId()==null || ids.contains(p.itemId())) && validAudioUrl(p.audioUrl()),"Invalid or duplicate pronunciation reference");
            require(p.ipa()==null || p.ipa().length()<=2000,"IPA is too long");
            if(p.pronunciationContext()!=null) {
                for(String field:List.of("form","usage","notation_system")) {
                    Object text=p.pronunciationContext().get(field);
                    require(text==null || text instanceof String value && value.length()<=2000,"Invalid pronunciation context");
                }
                Object system=p.pronunciationContext().get("notation_system");
                require(system==null || Set.of("IPA","SOURCE").contains(system),"Unsupported pronunciation notation");
            }
            if(publish) require(p.ipa()!=null && !p.ipa().isBlank() || p.audioUrl()!=null || p.assetId()!=null || p.audioFile()!=null,"Add IPA or a recording, or remove the empty pronunciation");
        }
    }
    private static boolean validAudioUrl(String value) {
        if(value==null) return true;
        try {var uri=java.net.URI.create(value);return Set.of("https","http").contains(uri.getScheme()) && uri.getHost()!=null && uri.getUserInfo()==null;}
        catch(IllegalArgumentException invalid) {return false;}
    }
    private static void require(boolean b,String message) {if(!b) throw new RequestValidationException(message);}
}
