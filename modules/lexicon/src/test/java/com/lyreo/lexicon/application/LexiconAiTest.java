package com.lyreo.lexicon.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.lyreo.lexicon.domain.LexiconDraft;
import com.lyreo.ai.application.execution.*;
import com.lyreo.contracts.errors.*;
import com.lyreo.platform.content.*;
import com.lyreo.platform.jobs.application.*;
import com.lyreo.platform.jobs.domain.BackgroundJob;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LexiconAiTest {
    final LexiconEditor editor=mock(LexiconEditor.class);
    final AiDraftRequests requests=mock(AiDraftRequests.class);
    final AiInvocationService invocation=mock(AiInvocationService.class);
    final ObjectMapper json=new ObjectMapper();
    final LexiconAi ai=new LexiconAi(editor,requests,invocation,json,mock(AssetStore.class));
    final UUID id=UUID.randomUUID(),sense=UUID.randomUUID(),item=UUID.randomUUID(),candidate=UUID.randomUUID(),requestId=UUID.randomUUID(),jobId=UUID.randomUUID();
    LexiconDraft draft() {return new LexiconDraft("book","WORD",null,null,null,List.of(new LexiconDraft.Item(item,"noun",null,null)),
        List.of(new LexiconDraft.Sense(sense,item,"Printed pages","sách","NEEDS_REVIEW",List.of(),List.of(),List.of(),null)),List.of(),List.of(),
        List.of(new LexiconDraft.Translation(candidate,null,null,"sổ","Dictionary","entry",null,"ENTRY_CANDIDATE","noun")));}
    DocumentStore.Document<LexiconDraft> document(long revision) {return new DocumentStore.Document<>(id,revision,"PUBLISHED",null,draft(),null,"admin");}
    AiDraftRequests.Request request(String operation,Map<String,Object> output,Map<String,Object> applied) {return new AiDraftRequests.Request(requestId,"lexicon",id,2,null,operation,Map.of("senses",draft().senses(),"candidates",draft().translations()),output,applied,jobId);}
    Map<String,Object> output(UUID candidateId) {return json.convertValue(new LexiconAi.Output(List.of(new LexiconAi.Suggestion(sense,"translationVi","sổ",candidateId,"Printed record")),List.of()),Map.class);}

    @Test void candidateMatchingIncludesSourceContextAndOnlyTheSelectedScope() {
        when(editor.get(id)).thenReturn(document(2));
        ai.request(id,2,List.of(sense),"","MATCH","admin");
        verify(requests).start(eq("lexicon"),eq(id),eq(2L),eq("MATCH"),argThat(input->input.get("candidates").equals(draft().translations()) && input.get("senses").equals(draft().senses())),eq("admin"),eq(ai.jobType()));
        assertThatThrownBy(()->ai.request(id,1,List.of(sense),"","MATCH","admin")).isInstanceOf(StateConflictException.class);
        assertThatThrownBy(()->ai.request(id,2,List.of(UUID.randomUUID()),"","MATCH","admin")).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(()->ai.request(id,2,List.of(sense),"","PUBLISH","admin")).isInstanceOf(RequestValidationException.class);
    }
    @Test void rejectsInventedCandidatesAndSuggestionsOutsideRequestedOperation() {
        assertThatThrownBy(()->ai.validate(json.convertValue(output(UUID.randomUUID()),LexiconAi.Output.class),request("MATCH",null,Map.of()))).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(()->ai.validate(json.convertValue(output(null),LexiconAi.Output.class),request("EXAMPLE",null,Map.of()))).isInstanceOf(RequestValidationException.class);
        ai.validate(json.convertValue(output(candidate),LexiconAi.Output.class),request("MATCH",null,Map.of()));
    }
    @Test void vietnameseOnlySenseRetainsItsSourceMeaningAndProvenanceInTheRequest() {
        var d=draft();
        var vi=new LexiconDraft.Sense(sense,item,null,"Quyển sách","AVAILABLE",List.of(),List.of(),List.of(),null,Map.of("vi","Quyển sách"),Map.of("source","viwiktionary","record_line",42));
        var source=new LexiconDraft(d.title(),d.type(),d.headwordId(),null,null,d.items(),List.of(vi),d.forms(),d.pronunciations(),d.translations());
        when(editor.get(id)).thenReturn(new DocumentStore.Document<>(id,2,"PUBLISHED",null,source,null,"admin"));
        ai.request(id,2,List.of(sense),"","EXAMPLE","admin");
        verify(requests).start(eq("lexicon"),eq(id),eq(2L),eq("EXAMPLE"),argThat(input->input.get("senses").equals(List.of(vi))),eq("admin"),eq(ai.jobType()));
    }
    @Test void acceptAppendsLinksTheCandidateAndDoesNotVerifyOrPublish() {
        when(requests.get(requestId,true)).thenReturn(request("MATCH",output(candidate),Map.of()));when(editor.get(id)).thenReturn(document(2));
        when(editor.save(eq(id),eq(2L),any(),eq("admin"))).thenAnswer(call->new DocumentStore.Document<>(id,3,"PUBLISHED",null,call.getArgument(2),null,"admin"));
        assertThatThrownBy(()->ai.accept(id,requestId,2,0,null,"admin")).isInstanceOf(RequestValidationException.class);
        var saved=ai.accept(id,requestId,2,0,"APPEND","admin");
        assertThat(saved.draft().senses().getFirst().translationVi()).isEqualTo("sách; sổ");
        assertThat(saved.draft().senses().getFirst().translationStatus()).isEqualTo("NEEDS_REVIEW");
        assertThat(saved.draft().translations().getFirst().senseId()).isEqualTo(sense);
        verify(requests).applied(requestId,3,"suggestion:0",true);
        when(requests.get(requestId,true)).thenReturn(request("MATCH",output(candidate),Map.of("suggestion:0",true)));
        ai.accept(id,requestId,2,0,"APPEND","admin");verify(editor,times(1)).save(any(),anyLong(),any(),any());
        verify(editor,never()).publish(any(),anyLong(),any(),any());
    }
    @Test void cancellationInvalidOutputAndStaleDraftNeverWrite() {
        var job=mock(BackgroundJob.class);var context=mock(JobExecutionContext.class);when(job.ownerReferenceId()).thenReturn(requestId);
        var row=request("TRANSLATE",null,Map.of());when(requests.get(requestId,false)).thenReturn(row);
        doThrow(new JobCancelledException("cancelled")).when(context).throwIfCancellationRequested();
        assertThatThrownBy(()->ai.execute(job,context)).isInstanceOf(JobCancelledException.class);verifyNoInteractions(invocation);
        when(invocation.execute(any(),any(),any(),any())).thenReturn(new AiExecutionResult(UUID.randomUUID(),"mock","mock",Map.of("structured",output(null),"finish_reason","length"),0,0,1));
        assertThatThrownBy(()->ai.execute(job,mock(JobExecutionContext.class))).isInstanceOf(PermanentJobException.class);
        verify(invocation).execute(any(),any(),any(),argThat(options->options.containsKey("json_schema") && options.get("response_schema").equals("library_lexicon_v2")));
        verify(requests,never()).complete(any(),any(),any(),any());
        when(requests.get(requestId,true)).thenReturn(request("TRANSLATE",output(null),Map.of()));when(editor.get(id)).thenReturn(document(3));
        doThrow(new StateConflictException("stale")).when(requests).requireRevision(any(),eq(3L),eq(3L));
        assertThatThrownBy(()->ai.accept(id,requestId,3,0,"REPLACE","admin")).isInstanceOf(StateConflictException.class);verify(editor,never()).save(any(),anyLong(),any(),any());
    }
    @Test void ttsMustUseAnExistingScopeAndReviewedHeadword() {
        when(editor.get(id)).thenReturn(document(2));
        assertThatThrownBy(()->ai.requestTts(id,2,"af_heart",false,item,"admin")).isInstanceOf(RequestValidationException.class);
        assertThatThrownBy(()->ai.requestTts(id,2,"af_heart",true,UUID.randomUUID(),"admin")).isInstanceOf(RequestValidationException.class);
        ai.requestTts(id,2,"af_heart",true,item,"admin");
        verify(requests).start(eq("lexicon"),eq(id),eq(2L),eq("TTS"),argThat(input->input.get("itemId").equals(item)),eq("admin"),eq(ai.jobType()));
    }
}
