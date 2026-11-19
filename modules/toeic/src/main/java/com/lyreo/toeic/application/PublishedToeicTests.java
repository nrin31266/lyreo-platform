package com.lyreo.toeic.application;

import com.lyreo.assessment.api.*;
import com.lyreo.contracts.errors.*;
import com.lyreo.entitlement.api.*;
import com.lyreo.platform.content.DocumentStore;
import com.lyreo.toeic.application.port.ToeicEditorSource;
import com.lyreo.toeic.domain.ToeicDraft;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class PublishedToeicTests {
    private final DocumentStore documents;private final ToeicEditorSource source;private final AssessmentLibrary questions;private final AssessmentSessions sessions;private final EntitlementService entitlements;
    public PublishedToeicTests(DocumentStore documents,ToeicEditorSource source,AssessmentLibrary questions,AssessmentSessions sessions,EntitlementService entitlements) {this.documents=documents;this.source=source;this.questions=questions;this.sessions=sessions;this.entitlements=entitlements;}
    public record Catalog(UUID id,String title,String type,Integer year,int questionCount,Integer listeningDurationSeconds,Integer readingDurationSeconds) {}
    public Catalog detail(UUID learnerId,UUID id) {
        var row=documents.find("toeic",id,ToeicDraft.class);
        if(row.isPresent() && !documents.sourceSelected("toeic",id) && Set.of("HIDDEN","ARCHIVED").contains(row.get().status())) throw new ResourceNotFoundException("TOEIC test unavailable");
        var publication=documents.published("toeic",id,ToeicDraft.class);
        var metadata=publication.map(p->new ToeicEditorSource.Metadata(p.content(),p.content().placements().size())).orElseGet(()->{
            var metadataSource=source.metadata(id).orElseThrow(()->new ResourceNotFoundException("TOEIC test unavailable"));
            if(metadataSource.sourceBlocked() || !"PUBLISHED".equals(source.status(id))) throw new ResourceNotFoundException("TOEIC test unavailable");
            return metadataSource;
        });
        var data=metadata.content();
        if(!"FULL_TEST".equals(data.type()) || metadata.questionCount()!=200) throw new ResourceNotFoundException("Only complete TOEIC tests are available");
        if("FEATURE".equals(data.accessMode())) entitlements.requireFeature(learnerId,FeatureKey.of(data.requiredFeatureKey()));
        return new Catalog(id,data.title(),data.type(),data.year(),metadata.questionCount(),data.listeningDurationSeconds(),data.readingDurationSeconds());
    }
    public List<Catalog> list(UUID learnerId,int page,int size) {
        if(page<0 || page>100000 || size<1 || size>100) throw new RequestValidationException("Invalid page");
        var result=new ArrayList<Catalog>();
        for(var summary:source.list("",null,"",page,size)) try {result.add(detail(learnerId,summary.id()));} catch(ResourceNotFoundException|org.springframework.security.access.AccessDeniedException unavailable) {}
        return result;
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public AssessmentSessions.View start(UUID learnerId,UUID id,String mode,List<UUID> selected) {
        if(mode==null || !Set.of("FULL_TEST","DRILL").contains(mode)) throw new RequestValidationException("Choose full test or drill");
        var data=content(learnerId,id);
        if(mode.equals("FULL_TEST") && (!data.type().equals("FULL_TEST") || selected!=null)) throw new RequestValidationException("A full test uses every question in a full-test publication");
        if(selected!=null && (selected.isEmpty() || selected.size()>200 || new HashSet<>(selected).size()!=selected.size() || !data.placements().stream().map(ToeicDraft.Placement::id).toList().containsAll(selected))) throw new RequestValidationException("Choose placements belonging to this test");
        var placements=data.placements().stream().filter(p->selected==null || selected.contains(p.id())).toList();
        var groupIds=new HashSet<UUID>();placements.forEach(p->{if(p.groupId()!=null) groupIds.add(p.groupId());});
        if(selected!=null && data.placements().stream().anyMatch(p->groupIds.contains(p.groupId()) && !selected.contains(p.id()))) throw new RequestValidationException("Select the complete question group to preserve its shared passage/audio");
        var items=placements.stream().map(p->new AssessmentSessions.Item(p.id(),p.itemId(),p.publicationId(),p.part(),p.questionNumber(),null,null,p.groupId(),questions.published(p.itemId(),p.publicationId()),p.gapNumber())).toList();
        var groups=data.groups().stream().filter(g->groupIds.contains(g.id())).map(g->new AssessmentSessions.Group(g.id(),g.part(),g.documents().stream().map(ToeicDraft.Document::html).toList(),g.media(),g.transcriptEn(),ReadingTranslation.hasMisalignedMarkers(g)?null:g.translationVi())).toList();
        UUID publication=documents.published("toeic",id,ToeicDraft.class).map(DocumentStore.Publication::id).orElse(null);
        return sessions.start(learnerId,"toeic",id,mode,new AssessmentSessions.Snapshot(data.title(),publication,items,groups));
    }
    private ToeicDraft content(UUID learnerId,UUID id) {
        var document=documents.find("toeic",id,ToeicDraft.class);
        if(document.isPresent() && !documents.sourceSelected("toeic",id) && Set.of("HIDDEN","ARCHIVED").contains(document.get().status())) throw new ResourceNotFoundException("TOEIC test unavailable");
        var published=documents.published("toeic",id,ToeicDraft.class);ToeicDraft data;
        if(published.isPresent()) data=published.get().content();
        else {
            data=source.find(id).orElseThrow(()->new ResourceNotFoundException("TOEIC test unavailable"));
            if (!"PUBLISHED".equals(source.status(id)) || data.groups().stream().anyMatch(group -> !group.sourceIssues().isEmpty()))
                throw new ResourceNotFoundException("TOEIC test unavailable");
        }
        if(!"FULL_TEST".equals(data.type()) || data.placements().size()!=200) throw new ResourceNotFoundException("Only complete TOEIC tests are available");
        if("FEATURE".equals(data.accessMode())) entitlements.requireFeature(learnerId,FeatureKey.of(data.requiredFeatureKey()));
        return data;
    }
}
