package com.lyreo.grammar.application;

import com.lyreo.assessment.api.*;
import com.lyreo.contracts.errors.*;
import com.lyreo.entitlement.api.*;
import com.lyreo.grammar.application.port.GrammarCatalogSource;
import com.lyreo.grammar.domain.GrammarCatalog;
import com.lyreo.platform.content.CatalogPage;
import com.lyreo.platform.content.DocumentStore;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class PublishedGrammarCatalogs {
    private final DocumentStore documents;private final GrammarCatalogSource source;private final GrammarCatalogEditor editor;
    private final EntitlementService entitlements;private final AssessmentLibrary questions;private final AssessmentSessions sessions;
    public PublishedGrammarCatalogs(DocumentStore documents,GrammarCatalogSource source,GrammarCatalogEditor editor,EntitlementService entitlements,AssessmentLibrary questions,AssessmentSessions sessions) {this.documents=documents;this.source=source;this.editor=editor;this.entitlements=entitlements;this.questions=questions;this.sessions=sessions;}

    public record Catalog(
        UUID id,
        String kind,
        String title,
        String titleEn,
        String description,
        String descriptionEn,
        UUID parentId,
        int questionCount,
        String accessMode,
        boolean locked,
        String requiredFeatureKey,
        String lockReason
    ) {
        public Catalog(UUID id,String kind,String title,String titleEn,String description,String descriptionEn,UUID parentId,int questionCount) {
            this(id,kind,title,titleEn,description,descriptionEn,parentId,questionCount,"PUBLIC",false,null,null);
        }
    }

    public Catalog detail(UUID learnerId,String kind,UUID id) {
        var metadata = visibleMetadata(kind, id);
        var data = metadata.content();
        GrammarCatalog parentData = null;
        if ("subtopic".equals(kind)) {
            parentData = visibleMetadata("topic", data.parentId()).content();
        }
        var lock = evaluateLock(
            learnerId,
            data.accessMode(),
            data.requiredFeatureKey(),
            parentData == null ? null : parentData.accessMode(),
            parentData == null ? null : parentData.requiredFeatureKey()
        );
        return new Catalog(
            id, kind, data.title(), data.titleEn(), data.description(), data.descriptionEn(),
            data.parentId(), metadata.questionCount(),
            lock.accessMode(), lock.locked(), lock.requiredFeatureKey(), lock.lockReason()
        );
    }

    private GrammarCatalogSource.Metadata visibleMetadata(String kind, UUID id) {
        String ns=editor.namespace(kind);var row=documents.find(ns,id,GrammarCatalog.class);
        if(row.isPresent() && !documents.sourceSelected(ns,id) && Set.of("HIDDEN","ARCHIVED").contains(row.get().status())) throw new ResourceNotFoundException("Grammar catalog unavailable");
        var publication=documents.published(ns,id,GrammarCatalog.class);
        var metadata=publication.map(p->new GrammarCatalogSource.Metadata(p.content(),p.content().questions().size())).orElseGet(()->{
            var metadataSource=source.metadata(kind,id).orElseThrow(()->new ResourceNotFoundException("Grammar catalog unavailable"));
            if(!"PUBLISHED".equals(source.status(kind,id))) throw new ResourceNotFoundException("Grammar catalog unavailable");
            return metadataSource;
        });
        return metadata;
    }

    public List<Catalog> list(UUID learnerId,String kind,int page,int size) {
        return list(learnerId,kind,null,page,size);
    }

    public List<Catalog> list(UUID learnerId,String kind,UUID parentId,int page,int size) {
        editor.namespace(kind);
        if(page<0 || page>100000 || size<1 || size>100) throw new RequestValidationException("Invalid page");
        return source.listPublished(kind, parentId, page, size).stream()
            .map(summary -> {
                var lock = evaluateLock(
                    learnerId,
                    summary.accessMode(),
                    summary.requiredFeatureKey(),
                    summary.parentAccessMode(),
                    summary.parentRequiredFeatureKey()
                );
                return new Catalog(
                    summary.id(),
                    summary.kind(),
                    summary.title(),
                    summary.titleEn(),
                    summary.description(),
                    summary.descriptionEn(),
                    summary.parentId(),
                    summary.questionCount(),
                    lock.accessMode(),
                    lock.locked(),
                    lock.requiredFeatureKey(),
                    lock.lockReason()
                );
            })
            .toList();
    }

    public CatalogPage<Catalog> page(UUID learnerId,String kind,UUID parentId,int page,int size) {
        var items = list(learnerId, kind, parentId, page, size);
        long total = source.countPublished(kind, parentId);
        return new CatalogPage<>(items, total, page, size);
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public AssessmentSessions.View start(UUID learnerId,String kind,UUID id,int limit,List<UUID> selected) {
        var prepared = prepareStart(learnerId, kind, id, limit, selected);
        return sessions.start(learnerId,"grammar",id,"PRACTICE",prepared.snapshot());
    }

    public AssessmentSessions.View start(UUID learnerId,String kind,UUID id,int limit,List<UUID> selected,String idempotencyKey) {
        var prepared = prepareStart(learnerId, kind, id, limit, selected);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return sessions.start(learnerId,"grammar",id,"PRACTICE",prepared.snapshot());
        }
        return sessions.startWithIdempotency(
            learnerId,
            "grammar",
            id,
            "PRACTICE",
            prepared.snapshot(),
            idempotencyKey,
            prepared.fingerprint()
        );
    }

    private record PreparedStart(AssessmentSessions.Snapshot snapshot, String fingerprint) {}

    private PreparedStart prepareStart(UUID learnerId,String kind,UUID id,int limit,List<UUID> selected) {
        if(limit<1 || limit>50) throw new RequestValidationException("Choose between 1 and 50 questions");
        var data=content(learnerId,kind,id);
        if(selected!=null && (selected.size()>50 || new HashSet<>(selected).size()!=selected.size() || !data.questions().stream().map(GrammarCatalog.QuestionRef::itemId).toList().containsAll(selected))) throw new RequestValidationException("Choose questions belonging to this catalog");
        var refs=data.questions().stream().filter(ref->selected==null || selected.contains(ref.itemId())).limit(limit).toList();
        var items=new ArrayList<AssessmentSessions.Item>();int number=0;
        for(var ref:refs) items.add(new AssessmentSessions.Item(ref.itemId(),ref.itemId(),ref.publicationId(),0,++number,kind.equals("topic")?id:data.parentId(),kind.equals("subtopic")?id:null,null,questions.published(ref.itemId(),ref.publicationId()),null));
        UUID publication=documents.published(editor.namespace(kind),id,GrammarCatalog.class).map(DocumentStore.Publication::id).orElse(null);
        String selectedPart = selected == null ? "" : selected.stream().map(UUID::toString).collect(Collectors.joining(","));
        String fingerprint = "grammar:" + kind + ":" + id + ":" + limit + ":" + selectedPart;
        return new PreparedStart(new AssessmentSessions.Snapshot(data.title(),publication,items,List.of()), fingerprint);
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

    private record LockInfo(String accessMode, boolean locked, String requiredFeatureKey, String lockReason) {}

    private LockInfo evaluateLock(
        UUID learnerId,
        String accessMode,
        String requiredFeatureKey,
        String parentAccessMode,
        String parentRequiredFeatureKey
    ) {
        String effectiveFeatureKey = null;
        if ("FEATURE".equals(accessMode) && requiredFeatureKey != null) {
            effectiveFeatureKey = requiredFeatureKey;
        } else if ("FEATURE".equals(parentAccessMode) && parentRequiredFeatureKey != null) {
            effectiveFeatureKey = parentRequiredFeatureKey;
        }
        if (effectiveFeatureKey == null) {
            return new LockInfo("PUBLIC", false, null, null);
        }
        if (entitlements == null) {
            return new LockInfo("FEATURE", true, effectiveFeatureKey, "FEATURE_GRANT_REQUIRED");
        }
        FeatureAccess access = entitlements.evaluateFeature(learnerId, FeatureKey.of(effectiveFeatureKey));
        boolean locked = !access.granted();
        return new LockInfo("FEATURE", locked, effectiveFeatureKey, locked ? access.lockReason() : null);
    }
}
