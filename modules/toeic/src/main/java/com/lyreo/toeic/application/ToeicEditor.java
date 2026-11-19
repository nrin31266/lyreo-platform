package com.lyreo.toeic.application;
import com.lyreo.toeic.domain.ToeicDraft;
import com.lyreo.toeic.application.port.*;
import com.lyreo.assessment.api.AssessmentLibrary;
import com.lyreo.assessment.domain.QuestionContent;
import com.lyreo.platform.content.*;
import com.lyreo.platform.content.DocumentStore.Document;
import com.lyreo.contracts.errors.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ToeicEditor {
    private final DocumentStore documents;private final ToeicEditorSource source;private final AssessmentLibrary items;private final AssetStore assets;private final PassageCleaner html;
    public ToeicEditor(DocumentStore documents,ToeicEditorSource source,AssessmentLibrary items,AssetStore assets,PassageCleaner html) {this.documents=documents;this.source=source;this.items=items;this.assets=assets;this.html=html;}
    public void requireRevision(UUID id,long revision) {documents.requireRevision("toeic",id,revision);}
    public record Issue(String code,String severity,String location,String message) {}
    public Document<ToeicDraft> get(UUID id) {
        if(documents.sourceSelected("toeic",id)) {
            var row=documents.find("toeic",id,ToeicDraft.class).orElseThrow();
            var raw=source.find(id).orElseThrow(()->new ResourceNotFoundException("Source content unavailable"));
            return new Document<>(id,row.revision(),source.status(id),null,raw,row.updatedAt(),row.updatedBy(),false);
        }
        var row=documents.find("toeic",id,ToeicDraft.class);
        if(row.isPresent() && row.get().publicationId()==null) return source.find(id).map(raw->documents.againstSource(row.get(),raw,source.status(id))).orElse(row.get());
        return row.orElseGet(()->{
        var data=source.find(id).orElseThrow(()->new ResourceNotFoundException("TOEIC test not found"));
        return new Document<>(id,0,source.status(id),null,data,null,null);
    });}
    public Document<ToeicDraft> save(UUID id,long revision,ToeicDraft data,String actor) {
        validateShape(data);
        validateStructure(data);
        var sourceGroups=source.find(id).stream().flatMap(d->d.groups().stream()).collect(java.util.stream.Collectors.toMap(ToeicDraft.Group::id,g->g));
        var groups=data.groups().stream().map(g->new ToeicDraft.Group(g.id(),g.part(),g.kind(),g.title(),g.transcriptEn(),g.translationVi(),g.vocabularyNote(),g.documents().stream().map(d->new ToeicDraft.Document(d.id(),d.ordinal(),d.type(),html.clean(d.html()))).toList(),g.media(),g.reviewed(),sourceGroups.containsKey(g.id())?sourceGroups.get(g.id()).sourceIssues():g.sourceIssues())).toList();
        var clean=new ToeicDraft(data.title(),data.code(),data.type(),data.year(),data.testNumber(),data.listeningDurationSeconds(),data.readingDurationSeconds(),data.accessMode(),data.requiredFeatureKey(),groups,data.placements());
        documents.save("toeic",id,revision,clean,actor,ToeicDraft.class);return get(id);
    }
    @Transactional public Document<ToeicDraft> publish(UUID id,long revision,String reason,String actor) {
        var data=get(id).draft();
        if(readiness(data).stream().anyMatch(i->i.severity().equals("ERROR"))) throw new StateConflictException("Resolve publication blockers first");
        var pinned=data.placements().stream().map(p->new ToeicDraft.Placement(p.id(),p.itemId(),items.pin(p.itemId(),p.publicationId()),p.groupId(),p.part(),p.questionNumber(),p.gapNumber())).toList();
        if(!pinned.equals(data.placements())) {
            var content=new ToeicDraft(data.title(),data.code(),data.type(),data.year(),data.testNumber(),data.listeningDurationSeconds(),data.readingDurationSeconds(),data.accessMode(),data.requiredFeatureKey(),data.groups(),pinned);
            revision=documents.save("toeic",id,revision,content,actor,ToeicDraft.class).revision();
        }
        return documents.publish("toeic",id,revision,reason,actor,ToeicDraft.class);
    }
    @Transactional public Document<ToeicDraft> restore(UUID id,long revision,UUID publicationId,String actor) {
        var data=documents.publication("toeic",id,publicationId,ToeicDraft.class).content();
        validateShape(data);validateStructure(data);
        if(readiness(data).stream().anyMatch(i->i.severity().equals("ERROR"))) throw new StateConflictException("Resolve publication blockers before restoring this test");
        return documents.restore("toeic",id,revision,publicationId,actor,ToeicDraft.class);
    }
    public void validateShape(ToeicDraft d) {
        require(d!=null && d.title()!=null && !d.title().isBlank() && d.title().length()<=500,"Title is required (up to 500 characters)");
        require(d.code()!=null && d.code().matches("[A-Za-z0-9._-]{1,100}"),"Use a code with letters, digits, dot, underscore or hyphen");
        require("FULL_TEST".equals(d.type()),"TOEIC content must be a complete 200-question Listening and Reading test; practice selection belongs to learner sessions");
        require(d.groups()!=null && d.placements()!=null && d.placements().size()<=200,"At most 200 questions are supported");
        require(d.accessMode()!=null && Set.of("PUBLIC","FEATURE").contains(d.accessMode()) && (d.accessMode().equals("PUBLIC")?d.requiredFeatureKey()==null:"toeic.full-access".equals(d.requiredFeatureKey())),"Invalid access policy");
        require((d.listeningDurationSeconds()==null || d.listeningDurationSeconds()>0 && d.listeningDurationSeconds()<=14400) && (d.readingDurationSeconds()==null || d.readingDurationSeconds()>0 && d.readingDurationSeconds()<=14400),"Duration must be between 1 and 14400 seconds");
        require(d.groups().size()<=100,"At most 100 groups per test");
        for(var g:d.groups()) {
            require(g!=null && g.id()!=null && g.documents()!=null && g.documents().size()<=3 && g.media()!=null,"Groups support up to three documents and require media collections");
            require(g.kind()!=null && Set.of("AUDIO","TEXT").contains(g.kind()) && g.media().size()<=10 && (g.transcriptEn()==null || g.transcriptEn().length()<=30000),"Invalid group kind, media or transcript");
            for(var media:g.media()) require(media!=null && media.assetId()!=null && media.role()!=null && Set.of("audio","image").contains(media.role()),"Invalid group media reference");
            for(var doc:g.documents()) require(doc!=null && doc.id()!=null && doc.html()!=null && doc.html().length()<=200000 && doc.ordinal()>=1 && doc.ordinal()<=3,"Invalid passage document");
        }
        for(var p:d.placements()) require(p!=null && p.itemId()!=null,"Invalid question placement");
    }
    public void validateStructure(ToeicDraft d) {
        int[] expected={6,25,39,30,30,16,54},counts=new int[7],ends={0,6,31,70,100,130,146,200};
        var numbers=new HashSet<Integer>();var ids=new HashSet<UUID>();
        for(var p:d.placements()) {
            require(p.id()!=null && ids.add(p.id()) && p.part()>=1 && p.part()<=7 && numbers.add(p.questionNumber()),"Question placements must have unique IDs and numbers within Parts 1–7");
            require(p.questionNumber()>ends[p.part()-1] && p.questionNumber()<=ends[p.part()],"Keep question numbers within their standard TOEIC Part range");
            counts[p.part()-1]++;
        }
        require(Arrays.equals(counts,expected),"TOEIC tests require exactly 200 questions: 100 Listening and 100 Reading, with Part counts 6/25/39/30/30/16/54");
    }
    public List<Issue> readiness(ToeicDraft d) {
        validateShape(d);var issues=new ArrayList<Issue>();var groups=new HashMap<UUID,ToeicDraft.Group>();var mediaChecks=new LinkedHashMap<String,List<QuestionContent.Media>>();
        for(var g:d.groups()) {
            if(groups.put(g.id(),g)!=null) error(issues,"DUPLICATE_GROUP",g.id().toString(),"Duplicate group ID");
            if(!Set.of(3,4,6,7).contains(g.part())) error(issues,"GROUP_PART",g.id().toString(),"Groups belong to Part 3, 4, 6 or 7");
            if(!Objects.equals(g.kind(),g.part()<=4?"AUDIO":"TEXT")) error(issues,"GROUP_KIND",g.id().toString(),"Group kind must match Listening or Reading");
            if(g.part()<=4 && g.media().stream().noneMatch(m->"audio".equals(m.role()))) error(issues,"MISSING_AUDIO",g.id().toString(),"Listening group needs audio");
            if(g.part()>=6 && (g.documents().isEmpty() || g.documents().stream().anyMatch(doc->!html.hasContent(doc.html())))) error(issues,"MISSING_PASSAGE",g.id().toString(),"Every reading document needs visible passage text");
            if(g.part()>=6 && g.media().stream().anyMatch(m->"audio".equals(m.role()))) error(issues,"READING_AUDIO",g.id().toString(),"Reading documents cannot contain listening audio");
            if(ReadingTranslation.hasMisalignedMarkers(g)) issues.add(new Issue("TRANSLATION_MARKERS","WARNING",g.id().toString(),"The Vietnamese passage has different sentence insertion markers. Check their positions against the English passage."));
            var ordinals=new HashSet<Integer>();var documentIds=new HashSet<UUID>();
            for(var doc:g.documents()) if(!ordinals.add(doc.ordinal()) || !documentIds.add(doc.id()) || doc.ordinal()>g.documents().size()) error(issues,"DOCUMENT_ORDER",g.id().toString(),"Documents need unique IDs and consecutive ordinals");
            for(var issue:g.sourceIssues()==null?List.<ToeicDraft.SourceIssue>of():g.sourceIssues()) {
                if(issue.code().equals("UNRESOLVED_IMAGE_REFERENCE") && g.media().stream().noneMatch(m->"image".equals(m.role()))) error(issues,issue.code(),g.id().toString(),"The source references a missing image. Restore the verified image before publication");
            }
            mediaChecks.put(g.id().toString(),g.media());
        }
        var numbers=new HashSet<Integer>();var ids=new HashSet<UUID>();var gaps=new HashSet<String>();int[] counts=new int[8];
        for(var p:d.placements()) {
            String location="Question "+p.questionNumber();
            if(p.id()==null || !ids.add(p.id()) || p.questionNumber()<1 || !numbers.add(p.questionNumber()) || p.part()<1 || p.part()>7) {error(issues,"PLACEMENT_INVALID",location,"Invalid or duplicate question identity/number/Part");continue;}
            counts[p.part()]++;
            int[] ends={0,6,31,70,100,130,146,200};
            if(d.type().equals("FULL_TEST") && (p.questionNumber()<=ends[p.part()-1] || p.questionNumber()>ends[p.part()])) error(issues,"QUESTION_NUMBER",location,"Question number is outside its full-test Part range");
            if(Set.of(3,4,6,7).contains(p.part()) && (p.groupId()==null || !groups.containsKey(p.groupId()) || groups.get(p.groupId()).part()!=p.part())) error(issues,"MISSING_GROUP",location,"Question must belong to a group in the same Part");
            if(p.part()<=2 || p.part()==5) {if(p.groupId()!=null) error(issues,"UNEXPECTED_GROUP",location,"This Part uses individual questions");}
            try {
                var q=items.published(p.itemId(),p.publicationId());AssessmentLibrary.validate(q,true);
                boolean cloze="CLOZE".equals(q.kind());
                if((p.part()==6)!=cloze) error(issues,"QUESTION_KIND",location,"Only Part 6 uses passage gaps; every Part 6 question must reference one");
                if(cloze && (p.part()!=6 || p.gapNumber()==null || p.gapNumber()<1 || !gaps.add(p.groupId()+":"+p.gapNumber()))) error(issues,"GAP_REFERENCE",location,"Cloze questions require Part 6 and a unique passage gap number");
                if(p.part()>=3 && ((!cloze && (q.title()==null || q.title().isBlank())) || q.options().stream().anyMatch(option->option.text().isBlank()))) error(issues,"CONTENT_EMPTY",location,"Questions require option text and a question stem, except passage-linked Part 6 gaps");
                if(q.options().size()!=(p.part()==2?3:4)) error(issues,"OPTION_COUNT",location,"Part 2 requires three options; other Parts require four");
                else if(!ToeicQuestionEditor.hasExpectedOptionKeys(p.part(),q)) error(issues,"OPTION_KEYS",location,"Part 2 requires option keys A, B and C; other Parts require A, B, C and D");
                if(p.part()<=2 && q.media().stream().noneMatch(m->"audio".equals(m.role()))) error(issues,"MISSING_AUDIO",location,"Listening question needs audio");
                if(p.part()==1 && q.media().stream().noneMatch(m->"image".equals(m.role()))) error(issues,"MISSING_IMAGE",location,"Part 1 question needs an image");
                if(p.part()>=5 && q.media().stream().anyMatch(m->"audio".equals(m.role()))) error(issues,"READING_AUDIO",location,"Reading questions cannot contain listening audio");
                mediaChecks.put(location,q.media());
            } catch(ResourceNotFoundException|RequestValidationException invalid) {error(issues,"QUESTION_INVALID",location,invalid.getMessage());}
        }
        if(d.placements().isEmpty()) error(issues,"EMPTY_TEST","Test","Add at least one question");
        int singleReadingQuestions=0,multipleReadingQuestions=0;
        for(var group:groups.values()) {
            var grouped=d.placements().stream().filter(p->group.id().equals(p.groupId())).map(ToeicDraft.Placement::questionNumber).sorted().toList();
            int required=group.part()==3 || group.part()==4?3:group.part()==6?4:0;
            if(!grouped.isEmpty() && (required>0?grouped.size()!=required:grouped.size()<2 || grouped.size()>5)) error(issues,"GROUP_QUESTION_COUNT",group.id().toString(),"Part 3/4 groups require 3 questions, Part 6 requires 4, Part 7 requires 2–5");
            if(grouped.isEmpty()) error(issues,"EMPTY_GROUP",group.id().toString(),"Remove this unused group or assign questions");
            else if(grouped.getLast()-grouped.getFirst()+1!=grouped.size()) error(issues,"GROUP_ORDER",group.id().toString(),"Questions sharing a group must remain consecutive");
            if(group.part()==6 && group.documents().size()!=1) error(issues,"PART6_DOCUMENTS",group.id().toString(),"Part 6 requires one text per four-question group");
            if(group.part()==7) {
                if(group.documents().size()==1) {
                    singleReadingQuestions+=grouped.size();
                    if(grouped.size()<2 || grouped.size()>4) error(issues,"SINGLE_PASSAGE_COUNT",group.id().toString(),"Single passages require 2–4 questions");
                    if(grouped.stream().anyMatch(number->number<147 || number>175)) error(issues,"PART7_ORDER",group.id().toString(),"Single-passage questions occupy numbers 147–175");
                } else if(group.documents().size()==2 || group.documents().size()==3) {
                    multipleReadingQuestions+=grouped.size();
                    if(grouped.size()!=5) error(issues,"MULTIPLE_PASSAGE_COUNT",group.id().toString(),"Multiple passages require five questions");
                    if(grouped.stream().anyMatch(number->number<176 || number>200)) error(issues,"PART7_ORDER",group.id().toString(),"Multiple-passage questions occupy numbers 176–200");
                } else error(issues,"PART7_DOCUMENTS",group.id().toString(),"Part 7 requires one single text or two/three related documents");
            }
        }
        if(singleReadingQuestions!=29 || multipleReadingQuestions!=25) error(issues,"PART7_DISTRIBUTION","Part 7","Part 7 requires 29 single-passage questions and 25 multiple-passage questions");
        if(d.type().equals("FULL_TEST") && (!Arrays.equals(counts,new int[]{0,6,25,39,30,30,16,54}) || numbers.size()!=200 || numbers.stream().anyMatch(n->n<1 || n>200))) error(issues,"PART_DISTRIBUTION","Test","Full tests require 200 questions with Part counts 6/25/39/30/30/16/54");
        var available=assets.available(mediaChecks.values().stream().flatMap(Collection::stream).filter(Objects::nonNull).map(QuestionContent.Media::assetId).filter(Objects::nonNull).distinct().toList());
        mediaChecks.forEach((location,media)->checkMedia(media,location,issues,available));
        return issues;
    }
    private void checkMedia(List<QuestionContent.Media> media,String location,List<Issue> issues,Map<UUID,AssetStore.Asset> available) {
        for(var m:media) {
            if(m==null || m.assetId()==null || m.role()==null || !Set.of("audio","image").contains(m.role())) {error(issues,"MEDIA_UNAVAILABLE",location,"Invalid media reference");continue;}
            var asset=available.get(m.assetId());
            if(asset==null || !asset.mimeType().startsWith(m.role()+"/")) error(issues,"MEDIA_UNAVAILABLE",location,"Referenced media is unavailable or has the wrong type");
        }
    }
    private static void error(List<Issue> issues,String code,String location,String text) {issues.add(new Issue(code,"ERROR",location,text));}
    private static void require(boolean condition,String message) {if(!condition) throw new RequestValidationException(message);}
}
