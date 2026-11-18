package com.lyreo.assessment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lyreo.assessment.api.AssessmentLibrary;
import com.lyreo.assessment.application.QuestionAi;
import com.lyreo.assessment.domain.QuestionContent;
import com.lyreo.ai.application.execution.*;
import com.lyreo.contracts.errors.*;
import com.lyreo.platform.content.*;
import com.lyreo.platform.jobs.application.*;
import com.lyreo.platform.jobs.domain.BackgroundJob;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class QuestionAiTest {
    final AssessmentLibrary library=mock(AssessmentLibrary.class);
    final AiDraftRequests requests=mock(AiDraftRequests.class);
    final AiInvocationService invocation=mock(AiInvocationService.class);
    final ObjectMapper json=new ObjectMapper();
    final QuestionAi ai=new QuestionAi(library,requests,invocation,json,mock(AssetStore.class));
    final UUID id=UUID.randomUUID(),requestId=UUID.randomUUID(),jobId=UUID.randomUUID();
    QuestionContent question() {return new QuestionContent("Choose","MCQ_SINGLE",List.of(new QuestionContent.Option("A","One"),new QuestionContent.Option("B","Two")),"A",1,null,null,null,null,null,List.of());}
    AiDraftRequests.Request request(Map<String,Object> output,Map<String,Object> applied) {return new AiDraftRequests.Request(requestId,"assessment",id,2,null,"SUGGEST",Map.of("count",20),output,applied,jobId);}
    DocumentStore.Document<QuestionContent> draft(long revision) {return new DocumentStore.Document<>(id,revision,"DRAFT",null,question(),null,"admin");}

    @Test void requestLedgerRejectsStaleDraftAndAcceptsItsOwnNextRevision() {
        var ledger=new AiDraftRequests(null,null,null);
        var row=request(Map.of(),Map.of());
        assertThatThrownBy(()->ledger.requireRevision(row,3,3)).isInstanceOf(StateConflictException.class);
        ledger.requireRevision(row,2,2);
        var accepted=new AiDraftRequests.Request(requestId,"assessment",id,2,3L,"SUGGEST",Map.of(),Map.of(),Map.of(),jobId);
        ledger.requireRevision(accepted,3,3);
        assertThatThrownBy(()->ledger.requireRevision(accepted,4,4)).isInstanceOf(StateConflictException.class);
        assertThatThrownBy(()->ledger.requireRevision(request(null,Map.of()),2,2)).isInstanceOf(StateConflictException.class);
    }

    @Test void synthesisRequiresReviewedTextAndDoesNotChangeDraftAutomatically() {
        when(library.get(id)).thenReturn(draft(2));
        assertThatThrownBy(()->ai.requestTts(id,2,"af_heart",false,"admin")).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(()->ai.requestTts(id,2,"af_heart",true,"admin")).isInstanceOf(RequestValidationException.class);
        verify(requests,never()).start(any(),any(),anyLong(),any(),any(),any(),any());
    }
    @Test void requestsIncludeThePassageReferenceAndSuppliedGapForHumanReview() {
        when(library.get(id)).thenReturn(draft(2));
        var context=List.of(Map.<String,Object>of("provenance","IMPORTED_REFERENCE","gap_number",131,"passage_html","<p>Gap (131)</p>"));
        when(library.context(id)).thenReturn(context);
        ai.request(id,2,"SUGGEST",20,"","admin");
        verify(requests).start(eq("assessment"),eq(id),eq(2L),eq("SUGGEST"),argThat(input->input.get("sourceContext").equals(context) && input.get("question").equals(question())),eq("admin"),eq(ai.jobType()));
    }

    @Test void untrustedOutputCannotChangeAnswersOrInventMedia() {
        assertThatThrownBy(()->QuestionAi.validate(new QuestionAi.Output(List.of(new QuestionAi.Suggestion("correctOption","B")),List.of(),List.of()),"SUGGEST",20)).isInstanceOf(RequestValidationException.class);
        var q=question();var invented=new QuestionContent(q.title(),q.kind(),q.options(),q.correctOption(),1,null,null,null,null,null,List.of(new QuestionContent.Media(UUID.randomUUID(),"audio")));
        assertThatThrownBy(()->QuestionAi.validate(new QuestionAi.Output(List.of(),List.of(invented),List.of()),"VARIANTS",1)).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(()->QuestionAi.validate(new QuestionAi.Output(List.of(new QuestionAi.Suggestion("difficultyLevel",2.5)),List.of(),List.of()),"SUGGEST",20)).isInstanceOf(RequestValidationException.class);
    }

    @Test void acceptanceOnlySavesSelectedDraftFieldAndRepeatedAcceptanceIsIdempotent() {
        var output=Map.<String,Object>of("suggestions",List.of(Map.of("field","translationVi","value","Chọn")),"candidates",List.of(),"warnings",List.of());
        when(requests.get(requestId,true)).thenReturn(request(output,Map.of()));
        when(library.get(id)).thenReturn(draft(2));
        when(library.save(eq(id),eq(2L),any(),eq("admin"))).thenAnswer(call->new DocumentStore.Document<>(id,3,"DRAFT",null,call.getArgument(2),null,"admin"));
        var saved=ai.accept(id,requestId,2,"suggestion",0,"admin");
        assertThat(saved.document().draft().translationVi()).isEqualTo("Chọn");
        assertThat(saved.document().draft().correctOption()).isEqualTo("A");
        verify(requests).applied(requestId,3,"suggestion:0",true);
        when(requests.get(requestId,true)).thenReturn(request(output,Map.of("suggestion:0",true)));
        ai.accept(id,requestId,2,"suggestion",0,"admin");
        verify(library,times(1)).save(any(),anyLong(),any(),any());
        verify(library,never()).publish(any(),anyLong(),any(),any());
    }

    @Test void staleDraftAndCancellationPreventAnyWrite() {
        var request=request(Map.of(),Map.of());
        when(requests.get(requestId,true)).thenReturn(request);when(library.get(id)).thenReturn(draft(3));
        doThrow(new StateConflictException("stale")).when(requests).requireRevision(request,3,3);
        assertThatThrownBy(()->ai.accept(id,requestId,3,"suggestion",0,"admin")).isInstanceOf(StateConflictException.class);
        verify(library,never()).save(any(),anyLong(),any(),any());
        var job=mock(BackgroundJob.class);var context=mock(JobExecutionContext.class);
        when(job.ownerReferenceId()).thenReturn(requestId);when(requests.get(requestId,false)).thenReturn(request(null,Map.of()));
        doThrow(new JobCancelledException("cancelled")).when(context).throwIfCancellationRequested();
        assertThatThrownBy(()->ai.execute(job,context)).isInstanceOf(JobCancelledException.class);
        verifyNoInteractions(invocation);verify(requests,never()).complete(any(),any(),any(),any());
    }

    @Test void invalidRuntimeOutputFailsWithoutSavingSuggestions() {
        var job=mock(BackgroundJob.class);when(job.ownerReferenceId()).thenReturn(requestId);
        when(requests.get(requestId,false)).thenReturn(request(null,Map.of()));
        when(invocation.execute(any(),any(),any(),any())).thenReturn(new AiExecutionResult(UUID.randomUUID(),"mock","mock",Map.of("structured",Map.of("suggestions",List.of(Map.of("field","correctOption","value","B")),"candidates",List.of(),"warnings",List.of())),0,0,1));
        assertThatThrownBy(()->ai.execute(job,mock(JobExecutionContext.class))).isInstanceOf(PermanentJobException.class).hasMessageContaining("draft was preserved");
        verify(requests,never()).complete(any(),any(),any(),any());
    }
}
