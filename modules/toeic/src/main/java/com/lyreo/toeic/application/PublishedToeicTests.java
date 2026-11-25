package com.lyreo.toeic.application;

import com.lyreo.assessment.api.*;
import com.lyreo.contracts.errors.*;
import com.lyreo.entitlement.api.*;
import com.lyreo.platform.content.CatalogPage;
import com.lyreo.platform.content.DocumentStore;
import com.lyreo.toeic.application.port.ToeicEditorSource;
import com.lyreo.toeic.domain.ToeicDraft;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class PublishedToeicTests {
    private final DocumentStore documents;private final ToeicEditorSource source;private final AssessmentLibrary questions;private final AssessmentSessions sessions;private final EntitlementService entitlements;
    public PublishedToeicTests(DocumentStore documents,ToeicEditorSource source,AssessmentLibrary questions,AssessmentSessions sessions,EntitlementService entitlements) {this.documents=documents;this.source=source;this.questions=questions;this.sessions=sessions;this.entitlements=entitlements;}

    public record PartBreakdown(
        int part,
        String section,
        int questionCount,
        int groupCount,
        List<UUID> placementIds
    ) {}

    public record Catalog(
        UUID id,
        String title,
        String type,
        Integer year,
        int questionCount,
        Integer listeningDurationSeconds,
        Integer readingDurationSeconds,
        String accessMode,
        boolean locked,
        String requiredFeatureKey,
        String lockReason,
        List<PartBreakdown> parts
    ) {
        public Catalog(UUID id,String title,String type,Integer year,int questionCount,Integer listeningDurationSeconds,Integer readingDurationSeconds) {
            this(id,title,type,year,questionCount,listeningDurationSeconds,readingDurationSeconds,"PUBLIC",false,null,null,List.of());
        }
    }

    public Catalog detail(UUID learnerId,UUID id) {
        var data = visiblePublishedDraft(id);
        var lock = evaluateLock(learnerId, data.accessMode(), data.requiredFeatureKey());
        return new Catalog(
            id,
            data.title(),
            data.type(),
            data.year(),
            data.placements().size(),
            data.listeningDurationSeconds(),
            data.readingDurationSeconds(),
            lock.accessMode(),
            lock.locked(),
            lock.requiredFeatureKey(),
            lock.lockReason(),
            partsBreakdown(data.placements())
        );
    }

    public List<Catalog> list(UUID learnerId,int page,int size) {
        if(page<0 || page>100000 || size<1 || size>100) throw new RequestValidationException("Invalid page");
        return source.listPublished(page, size).stream()
            .map(summary -> {
                var lock = evaluateLock(learnerId, summary.accessMode(), summary.requiredFeatureKey());
                return new Catalog(
                    summary.id(),
                    summary.title(),
                    summary.type(),
                    summary.year(),
                    summary.questionCount(),
                    summary.listeningDurationSeconds(),
                    summary.readingDurationSeconds(),
                    lock.accessMode(),
                    lock.locked(),
                    lock.requiredFeatureKey(),
                    lock.lockReason(),
                    List.of()
                );
            })
            .toList();
    }

    public CatalogPage<Catalog> page(UUID learnerId,int page,int size) {
        var items = list(learnerId, page, size);
        long total = source.countPublished();
        return new CatalogPage<>(items, total, page, size);
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public AssessmentSessions.View start(UUID learnerId,UUID id,String mode,List<UUID> selected) {
        var prepared = prepareStart(learnerId, id, mode, selected, null);
        return sessions.start(learnerId,"toeic",id,mode,prepared.snapshot());
    }

    public AssessmentSessions.View start(UUID learnerId,UUID id,String mode,List<UUID> selected,List<Integer> parts,String idempotencyKey) {
        var prepared = prepareStart(learnerId, id, mode, selected, parts);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return sessions.start(learnerId,"toeic",id,mode,prepared.snapshot());
        }
        return sessions.startWithIdempotency(
            learnerId,
            "toeic",
            id,
            mode,
            prepared.snapshot(),
            idempotencyKey,
            prepared.fingerprint()
        );
    }

    private record PreparedStart(AssessmentSessions.Snapshot snapshot, String fingerprint) {}

    private PreparedStart prepareStart(UUID learnerId,UUID id,String mode,List<UUID> selected,List<Integer> parts) {
        if(mode==null || !Set.of("FULL_TEST","DRILL").contains(mode)) throw new RequestValidationException("Choose full test or drill");
        var data=content(learnerId,id);
        if(mode.equals("FULL_TEST") && (!data.type().equals("FULL_TEST") || selected!=null || parts!=null)) {
            throw new RequestValidationException("A full test uses every question in a full-test publication");
        }
        if(selected!=null && parts!=null) {
            throw new RequestValidationException("Choose either placementIds or parts for a drill");
        }
        if(parts!=null) {
            if(parts.isEmpty() || parts.stream().anyMatch(p -> p == null || p < 1 || p > 7) || new HashSet<>(parts).size() != parts.size()) {
                throw new RequestValidationException("Choose distinct TOEIC parts between 1 and 7");
            }
        }
        if(selected!=null && (selected.isEmpty() || selected.size()>200 || new HashSet<>(selected).size()!=selected.size() || !data.placements().stream().map(ToeicDraft.Placement::id).toList().containsAll(selected))) {
            throw new RequestValidationException("Choose placements belonging to this test");
        }
        List<ToeicDraft.Placement> placements;
        if(parts != null) {
            var partSet = new HashSet<>(parts);
            placements = data.placements().stream().filter(p -> partSet.contains(p.part())).toList();
            if(placements.isEmpty()) {
                throw new RequestValidationException("Choose TOEIC parts belonging to this test");
            }
        } else {
            placements = data.placements().stream().filter(p -> selected == null || selected.contains(p.id())).toList();
        }
        var groupIds=new HashSet<UUID>();placements.forEach(p->{if(p.groupId()!=null) groupIds.add(p.groupId());});
        if(selected!=null && data.placements().stream().anyMatch(p->groupIds.contains(p.groupId()) && !selected.contains(p.id()))) throw new RequestValidationException("Select the complete question group to preserve its shared passage/audio");
        var imported = questions.importedQuestions(placements.stream()
            .filter(p -> p.publicationId() == null).map(ToeicDraft.Placement::itemId).distinct().toList());
        var items = placements.stream().map(p -> {
            var content = p.publicationId() == null ? imported.get(p.itemId()) : null;
            if (content == null) content = questions.published(p.itemId(), p.publicationId());
            return new AssessmentSessions.Item(p.id(), p.itemId(), p.publicationId(), p.part(), p.questionNumber(),
                null, null, p.groupId(), content, p.gapNumber());
        }).toList();
        var groups=data.groups().stream().filter(g->groupIds.contains(g.id())).map(g->new AssessmentSessions.Group(g.id(),g.part(),g.documents().stream().map(ToeicDraft.Document::html).toList(),g.media(),g.transcriptEn(),ReadingTranslation.hasMisalignedMarkers(g)?null:g.translationVi())).toList();
        UUID publication=documents.published("toeic",id,ToeicDraft.class).map(DocumentStore.Publication::id).orElse(null);
        String selectedPart = selected == null ? "" : selected.stream().map(UUID::toString).collect(Collectors.joining(","));
        String partsPart = parts == null ? "" : parts.stream().map(String::valueOf).collect(Collectors.joining(","));
        String fingerprint = "toeic:" + id + ":" + mode + ":" + partsPart + ":" + selectedPart;
        return new PreparedStart(new AssessmentSessions.Snapshot(data.title(),publication,items,groups), fingerprint);
    }

    private ToeicDraft visiblePublishedDraft(UUID id) {
        var document=documents.find("toeic",id,ToeicDraft.class);
        if(document.isPresent() && !documents.sourceSelected("toeic",id) && Set.of("HIDDEN","ARCHIVED").contains(document.get().status())) throw new ResourceNotFoundException("TOEIC test unavailable");
        var published=documents.published("toeic",id,ToeicDraft.class);
        ToeicDraft data;
        if(published.isPresent()) data=published.get().content();
        else {
            data=source.find(id).orElseThrow(()->new ResourceNotFoundException("TOEIC test unavailable"));
            if (!"PUBLISHED".equals(source.status(id)) || data.groups().stream().anyMatch(group -> !group.sourceIssues().isEmpty()))
                throw new ResourceNotFoundException("TOEIC test unavailable");
        }
        if(!"FULL_TEST".equals(data.type()) || data.placements().size()!=200) throw new ResourceNotFoundException("Only complete TOEIC tests are available");
        return data;
    }

    private ToeicDraft content(UUID learnerId,UUID id) {
        ToeicDraft data = visiblePublishedDraft(id);
        if("FEATURE".equals(data.accessMode())) entitlements.requireFeature(learnerId,FeatureKey.of(data.requiredFeatureKey()));
        return data;
    }

    private List<PartBreakdown> partsBreakdown(List<ToeicDraft.Placement> placements) {
        Map<Integer, List<ToeicDraft.Placement>> byPart = new TreeMap<>();
        for (var placement : placements) {
            byPart.computeIfAbsent(placement.part(), ignored -> new ArrayList<>()).add(placement);
        }
        List<PartBreakdown> result = new ArrayList<>();
        for (var entry : byPart.entrySet()) {
            int part = entry.getKey();
            var list = entry.getValue();
            String section = part <= 4 ? "LISTENING" : "READING";
            int groupCount = (int) list.stream()
                .map(ToeicDraft.Placement::groupId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
            List<UUID> placementIds = list.stream()
                .map(ToeicDraft.Placement::id)
                .toList();
            result.add(new PartBreakdown(part, section, list.size(), groupCount, placementIds));
        }
        return result;
    }

    private record LockInfo(String accessMode, boolean locked, String requiredFeatureKey, String lockReason) {}

    private LockInfo evaluateLock(UUID learnerId, String accessMode, String requiredFeatureKey) {
        if (!"FEATURE".equals(accessMode) || requiredFeatureKey == null) {
            return new LockInfo("PUBLIC", false, null, null);
        }
        if (entitlements == null) {
            return new LockInfo("FEATURE", true, requiredFeatureKey, "FEATURE_GRANT_REQUIRED");
        }
        FeatureAccess access = entitlements.evaluateFeature(learnerId, FeatureKey.of(requiredFeatureKey));
        boolean locked = !access.granted();
        return new LockInfo("FEATURE", locked, requiredFeatureKey, locked ? access.lockReason() : null);
    }
}
