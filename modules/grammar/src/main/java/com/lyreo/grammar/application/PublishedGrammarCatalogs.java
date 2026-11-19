package com.lyreo.grammar.application;

import com.lyreo.assessment.api.*;
import com.lyreo.contracts.errors.*;
import com.lyreo.entitlement.api.*;
import com.lyreo.grammar.application.port.GrammarCatalogSource;
import com.lyreo.grammar.domain.GrammarCatalog;
import com.lyreo.platform.content.DocumentStore;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class PublishedGrammarCatalogs {
    private final DocumentStore documents;private final GrammarCatalogSource source;private final GrammarCatalogEditor editor;
    private final EntitlementService entitlements;private final AssessmentLibrary questions;private final AssessmentSessions sessions;
    public PublishedGrammarCatalogs(DocumentStore documents,GrammarCatalogSource source,GrammarCatalogEditor editor,EntitlementService entitlements,AssessmentLibrary questions,AssessmentSessions sessions) {this.documents=documents;this.source=source;this.editor=editor;this.entitlements=entitlements;this.questions=questions;this.sessions=sessions;}
    public record Catalog(UUID id,String kind,String title,String titleEn,String description,String descriptionEn,UUID parentId,int questionCount) {}

    public Catalog detail(UUID learnerId,String kind,UUID id) {
        var metadata = visibleMetadata(learnerId, kind, id);
        var data = metadata.content();
        return new Catalog(id,kind,data.title(),data.titleEn(),data.description(),data.descriptionEn(),data.parentId(),metadata.questionCount());
    }

    private GrammarCatalogSource.Metadata visibleMetadata(UUID learnerId, String kind, UUID id) {
        String ns=editor.namespace(kind);var row=documents.find(ns,id,GrammarCatalog.class);
        if(row.isPresent() && !documents.sourceSelected(ns,id) && Set.of("HIDDEN","ARCHIVED").contains(row.get().status())) throw new ResourceNotFoundException("Grammar catalog unavailable");
        var publication=documents.published(ns,id,GrammarCatalog.class);
        var metadata=publication.map(p->new GrammarCatalogSource.Metadata(p.content(),p.content().questions().size())).orElseGet(()->{
            var metadataSource=source.metadata(kind,id).orElseThrow(()->new ResourceNotFoundException("Grammar catalog unavailable"));
            if(!"PUBLISHED".equals(source.status(kind,id))) throw new ResourceNotFoundException("Grammar catalog unavailable");
            return metadataSource;
        });
        var data=metadata.content();
        if("FEATURE".equals(data.accessMode())) entitlements.requireFeature(learnerId,FeatureKey.of(data.requiredFeatureKey()));
        if(kind.equals("subtopic")) visibleMetadata(learnerId,"topic",data.parentId());
        return metadata;
    }

    public List<Catalog> list(UUID learnerId,String kind,int page,int size) {
        editor.namespace(kind);
        if(page<0 || page>100000 || size<1 || size>100) throw new RequestValidationException("Invalid page");
        var result=new ArrayList<Catalog>();
        for(var summary:source.list(kind,"",null,page,size)) try {result.add(detail(learnerId,kind,summary.id()));} catch(ResourceNotFoundException|org.springframework.security.access.AccessDeniedException unavailable) {}
        return result;
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public AssessmentSessions.View start(UUID learnerId,String kind,UUID id,int limit,List<UUID> selected) {
        if(limit<1 || limit>50) throw new RequestValidationException("Choose between 1 and 50 questions");
        var data=content(learnerId,kind,id);
        if(selected!=null && (selected.size()>50 || new HashSet<>(selected).size()!=selected.size() || !data.questions().stream().map(GrammarCatalog.QuestionRef::itemId).toList().containsAll(selected))) throw new RequestValidationException("Choose questions belonging to this catalog");
        var refs=data.questions().stream().filter(ref->selected==null || selected.contains(ref.itemId())).limit(limit).toList();
        var items=new ArrayList<AssessmentSessions.Item>();int number=0;
        for(var ref:refs) items.add(new AssessmentSessions.Item(ref.itemId(),ref.itemId(),ref.publicationId(),0,++number,kind.equals("topic")?id:data.parentId(),kind.equals("subtopic")?id:null,null,questions.published(ref.itemId(),ref.publicationId()),null));
        UUID publication=documents.published(editor.namespace(kind),id,GrammarCatalog.class).map(DocumentStore.Publication::id).orElse(null);
        return sessions.start(learnerId,"grammar",id,"PRACTICE",new AssessmentSessions.Snapshot(data.title(),publication,items,List.of()));
    }
    private GrammarCatalog content(UUID learnerId,String kind,UUID id) {
        String ns=editor.namespace(kind);var document=documents.find(ns,id,GrammarCatalog.class);
        if(document.isPresent() && !documents.sourceSelected(ns,id) && Set.of("HIDDEN","ARCHIVED").contains(document.get().status())) throw new ResourceNotFoundException("Grammar catalog unavailable");
        var published=documents.published(ns,id,GrammarCatalog.class);
        GrammarCatalog data;
        if(published.isPresent()) data=published.get().content();
        else {data=source.find(kind,id).orElseThrow(()->new ResourceNotFoundException("Grammar catalog unavailable"));if(!"PUBLISHED".equals(source.status(kind,id))) throw new ResourceNotFoundException("Grammar catalog unavailable");}
        if("FEATURE".equals(data.accessMode())) entitlements.requireFeature(learnerId,FeatureKey.of(data.requiredFeatureKey()));
        if(kind.equals("subtopic")) content(learnerId,"topic",data.parentId());
        return data;
    }
}
