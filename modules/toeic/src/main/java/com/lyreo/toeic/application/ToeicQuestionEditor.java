package com.lyreo.toeic.application;
import com.lyreo.assessment.api.*;
import com.lyreo.assessment.domain.QuestionContent;
import com.lyreo.toeic.domain.ToeicDraft;
import com.lyreo.platform.content.*;
import com.lyreo.contracts.errors.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ToeicQuestionEditor {
    private final ToeicEditor tests;private final AssessmentLibrary questions;private final AssessmentEditorial ai;
    public ToeicQuestionEditor(ToeicEditor tests,AssessmentLibrary questions,AssessmentEditorial ai) {this.tests=tests;this.questions=questions;this.ai=ai;}
    private ToeicDraft.Placement placement(UUID id,UUID placement) {return tests.get(id).draft().placements().stream().filter(p->p.id().equals(placement)).findFirst().orElseThrow(()->new ResourceNotFoundException("Question placement not found in this test"));}
    private void revision(UUID id,long revision) {tests.requireRevision(id,revision);if(tests.get(id).revision()!=revision) throw new StateConflictException("The test changed; save or reload it before editing its questions");}
    public DocumentStore.Document<QuestionContent> get(UUID id,UUID placement) {return questions.get(placement(id,placement).itemId());}
    @Transactional public DocumentStore.Document<QuestionContent> draft(UUID id,UUID placement,long testRevision,long questionRevision,QuestionContent content,String actor) {
        revision(id,testRevision);var selected=placement(id,placement);validatePart(selected.part(),content);return questions.save(selected.itemId(),questionRevision,content,actor);
    }
    @Transactional public DocumentStore.Document<ToeicDraft> save(UUID id,UUID placement,long testRevision,long questionRevision,QuestionContent content,String actor) {
        revision(id,testRevision);var selected=placement(id,placement);validatePart(selected.part(),content);
        var saved=questions.save(selected.itemId(),questionRevision,content,actor);
        var publication=questions.publish(saved.id(),saved.revision(),null,actor);
        var current=tests.get(id).draft();
        var placements=current.placements().stream().map(p->p.id().equals(placement)?new ToeicDraft.Placement(p.id(),p.itemId(),publication.publicationId(),p.groupId(),p.part(),p.questionNumber(),p.gapNumber()):p).toList();
        return tests.save(id,testRevision,new ToeicDraft(current.title(),current.code(),current.type(),current.year(),current.testNumber(),current.listeningDurationSeconds(),current.readingDurationSeconds(),current.accessMode(),current.requiredFeatureKey(),current.groups(),placements),actor);
    }
    public static void validatePart(int part,QuestionContent q) {
        AssessmentLibrary.validate(q,true);
        if(q.options().size()!=(part==2?3:4)) throw new RequestValidationException("Part 2 requires three options; all other Parts require four");
        if(!hasExpectedOptionKeys(part,q)) throw new RequestValidationException("Part 2 requires option keys A, B and C; all other Parts require A, B, C and D");
        if((part==6)!=q.kind().equals("CLOZE")) throw new RequestValidationException("Only Part 6 uses passage gaps");
        if(part>=5 && q.media().stream().anyMatch(m->m.role().equals("audio"))) throw new RequestValidationException("Reading questions cannot contain listening audio");
    }
    static boolean hasExpectedOptionKeys(int part,QuestionContent q) {
        return (part==2?Set.of("A","B","C"):Set.of("A","B","C","D")).equals(q.options().stream().map(QuestionContent.Option::key).collect(java.util.stream.Collectors.toSet()));
    }
    public List<AiDraftRequests.Request> history(UUID id,UUID placement) {return ai.history(placement(id,placement).itemId()).stream().filter(r->r.input().get("sourceContext") instanceof Map<?,?> c && id.toString().equals(c.get("testId"))).toList();}
    @Transactional public AiDraftRequests.Request suggest(UUID id,UUID placement,long testRevision,long questionRevision,String brief,String actor) {
        revision(id,testRevision);var p=placement(id,placement);var current=tests.get(id);
        var context=new HashMap<String,Object>();context.put("testId",id.toString());context.put("testRevision",testRevision);context.put("placementId",placement.toString());context.put("part",p.part());context.put("questionNumber",p.questionNumber());context.put("gapNumber",p.gapNumber());
        current.draft().groups().stream().filter(g->g.id().equals(p.groupId())).findFirst().ifPresent(g->context.put("group",g));
        return ai.suggest(p.itemId(),questionRevision,brief,actor,context);
    }
    @Transactional public DocumentStore.Document<QuestionContent> accept(UUID id,UUID placement,UUID request,long testRevision,long questionRevision,String kind,int index,String actor) {
        revision(id,testRevision);var p=placement(id,placement);var row=ai.request(request);
        if(!(row.input().get("sourceContext") instanceof Map<?,?> c) || !id.toString().equals(c.get("testId")) || !placement.toString().equals(c.get("placementId")) || !(c.get("testRevision") instanceof Number n) || n.longValue()!=testRevision) throw new StateConflictException("The test context changed; request fresh suggestions");
        if(!"suggestion".equals(kind)) throw new RequestValidationException("Only field suggestions can be accepted within a full TOEIC test");
        return ai.accept(p.itemId(),request,questionRevision,kind,index,actor);
    }
}
