package com.lyreo.lesson;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lyreo.ai.application.execution.AiExecutionResult;
import com.lyreo.ai.application.execution.AiInvocationService;
import com.lyreo.ai.domain.AiCapability;
import com.lyreo.lesson.application.authoring.*;
import com.lyreo.lesson.application.port.AuthoringRepository;
import com.lyreo.lesson.application.port.AuthoringSourcePort;
import com.lyreo.lesson.domain.authoring.AuthoringLesson;
import com.lyreo.lesson.domain.authoring.AuthoringSentence;
import com.lyreo.lexicon.api.LexiconLinkService;
import com.lyreo.platform.jobs.application.*;
import com.lyreo.platform.jobs.domain.BackgroundJob;
import com.lyreo.platform.storage.ObjectStoragePort;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class LessonAuthoringJobHandlerTest {
    private final AuthoringRepository repository=mock(AuthoringRepository.class);
    private final AiInvocationService ai=mock(AiInvocationService.class);
    private final AuthoringSemantic semantic=spy(new AuthoringSemantic(new ObjectMapper(),mock(LexiconLinkService.class)));
    private final AuthoringJobCommitter committer=mock(AuthoringJobCommitter.class);
    private final BackgroundJob job=mock(BackgroundJob.class);
    private final JobExecutionContext context=mock(JobExecutionContext.class,CALLS_REAL_METHODS);
    private final ObjectStoragePort storage=mock(ObjectStoragePort.class);
    private final LessonAuthoringJobHandler handler=new LessonAuthoringJobHandler(repository,mock(AuthoringSourcePort.class),
        storage,ai,semantic,committer);
    private AuthoringLesson lesson;
    private AuthoringSentence sentence;

    @BeforeEach void setup() {
        lesson=new AuthoringLesson();lesson.id=UUID.randomUUID();lesson.transcriptRevision=1;
        lesson.approvedRevision=1L;lesson.karaokeReviewedRevision=1L;lesson.timingStatus="VALID";
        lesson.activeJobId=UUID.randomUUID();when(job.id()).thenReturn(lesson.activeJobId);
        sentence=new AuthoringSentence(UUID.randomUUID(),"We learn English.","LEARN");
        var watch=new AuthoringSentence(UUID.randomUUID(),"Context only.","WATCH_ONLY");
        lesson.sentences=List.of(sentence,watch);
        when(repository.find(lesson.id,false)).thenReturn(Optional.of(lesson));
        when(repository.jobInput(job.id())).thenReturn(new AuthoringRepository.JobInput(lesson.id,"SEMANTIC",1,false));
        when(repository.chunk(job.id(),0)).thenReturn(Optional.empty());
    }
    @Test void unsupportedProviderEndsWithActionableConfigurationErrorWithoutRetrying() {
        when(ai.execute(eq(AiCapability.GENERAL_LLM),anyString(),anyMap(),anyMap()))
            .thenThrow(new com.lyreo.ai.application.execution.AiExecutionException("private runtime detail",true,"AI_HTTP_501"));
        assertThatThrownBy(()->handler.execute(job,context)).isInstanceOfSatisfying(PermanentJobException.class,
            failure->{assertThat(failure.errorCode()).isEqualTo("AI_CONFIGURATION_REQUIRED");
                assertThat(failure.getMessage()).contains("AI Settings").doesNotContain("private runtime detail");});
        verifyNoInteractions(committer);
    }
    @Test void correctsInvalidChunkOnceWithSameContextSchemaBudgetAndSafeFeedback() throws Exception {
        when(ai.execute(eq(AiCapability.GENERAL_LLM),anyString(),anyMap(),anyMap()))
            .thenReturn(response("not present credential=secret"),response(null));
        handler.execute(job,context);
        var prompts=ArgumentCaptor.forClass(String.class);
        var inputs=ArgumentCaptor.forClass(Map.class);
        var options=ArgumentCaptor.forClass(Map.class);
        verify(ai,times(2)).execute(eq(AiCapability.GENERAL_LLM),prompts.capture(),inputs.capture(),options.capture());
        assertThat(prompts.getAllValues().get(1)).contains("Annotation surface is missing or ambiguous", "vocabulary [], grammar [], hints [], notes [], thoughtGroups []")
            .doesNotContain("credential=secret");
        assertThat(inputs.getAllValues().get(1)).isEqualTo(inputs.getAllValues().get(0));
        assertThat((List<?>)inputs.getValue().get("context")).hasSize(2);
        assertThat(options.getAllValues().get(1)).isEqualTo(options.getAllValues().get(0));
        assertThat(sentence.translation).isEqualTo("Chúng tôi học tiếng Anh.");
        verify(committer).stage(job,0,List.of(sentence));
        verify(committer).commit(eq(job),any(),eq(lesson));
        verify(repository,times(2)).usage(eq(job.id()),any());
    }
    @Test void secondInvalidResponseFailsPermanentlyWithoutStagingOrCommitting() {
        when(ai.execute(eq(AiCapability.GENERAL_LLM),anyString(),anyMap(),anyMap()))
            .thenReturn(response("missing"),response("still missing"));
        assertThatThrownBy(()->handler.execute(job,context)).isInstanceOfSatisfying(PermanentJobException.class,
            failure->assertThat(failure.errorCode()).isEqualTo("AUTHORING_VALIDATION_FAILED"));
        verify(ai,times(2)).execute(any(),anyString(),anyMap(),anyMap());
        verifyNoInteractions(committer);
    }
    @Test void cancellationBetweenAttemptsPreventsAnotherProviderCall() {
        var cancelled=new AtomicBoolean();
        when(context.isCancellationRequested()).thenAnswer(call->cancelled.get());
        when(ai.execute(eq(AiCapability.GENERAL_LLM),anyString(),anyMap(),anyMap())).thenReturn(response("missing"));
        doAnswer(call->{
            try {return call.callRealMethod();}
            finally {cancelled.set(true);}
        }).when(semantic).apply(anyMap(),anyList());
        assertThatThrownBy(()->handler.execute(job,context)).isInstanceOf(JobCancelledException.class);
        verify(ai,times(1)).execute(any(),anyString(),anyMap(),anyMap());
        verifyNoInteractions(committer);
    }
    @Test void lostLeaseBetweenAttemptsPreventsAnotherProviderCall() {
        when(ai.execute(eq(AiCapability.GENERAL_LLM),anyString(),anyMap(),anyMap())).thenReturn(response("missing"));
        doNothing().doThrow(new JobLeaseLostException("reassigned")).when(context).heartbeat();
        assertThatThrownBy(()->handler.execute(job,context)).isInstanceOf(JobLeaseLostException.class);
        verify(ai,times(1)).execute(any(),anyString(),anyMap(),anyMap());
        verifyNoInteractions(committer);
    }
    @Test void resumesAcceptedChunksAndOnlyCorrectsTheUnfinishedChunk() throws Exception {
        var cached=new ArrayList<AuthoringSentence>();
        for(int i=0;i<6;i++) {
            var saved=new AuthoringSentence(UUID.randomUUID(),"Previously validated "+i+".","LEARN");
            saved.translation="Đã kiểm tra.";cached.add(saved);
        }
        var all=new ArrayList<>(cached);all.add(sentence);lesson.sentences=all;
        when(repository.chunk(job.id(),0)).thenReturn(Optional.of(cached));
        when(repository.chunk(job.id(),6)).thenReturn(Optional.empty());
        when(ai.execute(eq(AiCapability.GENERAL_LLM),anyString(),anyMap(),anyMap()))
            .thenReturn(response("missing"),response(null));
        handler.execute(job,context);
        verify(ai,times(2)).execute(any(),anyString(),anyMap(),anyMap());
        verify(committer,never()).stage(eq(job),eq(0),anyList());
        verify(committer).stage(job,6,List.of(sentence));
        assertThat(cached).allSatisfy(saved->assertThat(saved.translation).isEqualTo("Đã kiểm tra."));
    }
    @Test void providerFailureDoesNotConsumeValidationCorrectionAttempt() {
        when(ai.execute(eq(AiCapability.GENERAL_LLM),anyString(),anyMap(),anyMap())).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(()->handler.execute(job,context)).isInstanceOf(IllegalStateException.class).hasMessage("offline");
        verify(ai,times(1)).execute(any(),anyString(),anyMap(),anyMap());
        verifyNoInteractions(committer);
    }
    @Test void alignmentCommitsObservedPhraseGroupsWithoutChangingApprovedText() throws Exception {
        alignment(List.of(Map.of("text","We","start_ms",100,"end_ms",200),
            Map.of("text","learn","start_ms",250,"end_ms",250),
            Map.of("text","English","start_ms",300,"end_ms",500)));
        handler.execute(job,context);
        assertThat(sentence.text).isEqualTo("We learn English.");
        assertThat(sentence.words).hasSize(2);
        assertThat(sentence.words.getLast().text()).isEqualTo("learn English");
        assertThat(sentence.words.getLast().startMs()).isEqualTo(250);
        assertThat(sentence.words.getLast().endMs()).isEqualTo(500);
        assertThat(sentence.issues).contains("GROUPED_TIMING").doesNotContain("TIMING_ZERO_DURATION");
        verify(committer).commit(eq(job),any(),eq(lesson));
    }
    @Test void alignmentWithNoPositiveObservationStillFailsWithoutCommitting() {
        alignment(List.of(Map.of("text","We","start_ms",100,"end_ms",100),
            Map.of("text","learn","start_ms",200,"end_ms",200),
            Map.of("text","English","start_ms",300,"end_ms",300)));
        assertThatThrownBy(()->handler.execute(job,context)).isInstanceOfSatisfying(PermanentJobException.class,
            failure->assertThat(failure.errorCode()).isEqualTo("TIMING_ZERO_DURATION"));
        verifyNoInteractions(committer);
    }
    private void alignment(List<Map<String,Object>> words) {
        lesson.sentences=List.of(sentence);lesson.endMs=1000;lesson.audioObjectKey="test.wav";
        sentence.coarseStartMs=0L;sentence.coarseEndMs=1000L;
        when(storage.createDownloadUrl(anyString(),any())).thenReturn(java.net.URI.create("http://storage/test.wav"));
        when(repository.jobInput(job.id())).thenReturn(new AuthoringRepository.JobInput(lesson.id,"ALIGN",1,false));
        when(ai.execute(eq(AiCapability.ALIGNMENT),anyString(),anyMap(),anyMap()))
            .thenReturn(new AiExecutionResult(UUID.randomUUID(),"QWEN","test",Map.of("words",words),1,1,10));
    }
    private AiExecutionResult response(String invalidSurface) {
        var item=new HashMap<String,Object>();
        item.put("sentenceId",sentence.id.toString());item.put("translation","Chúng tôi học tiếng Anh.");
        item.put("dictationPriority","MEDIUM");item.put("shadowingPriority","MEDIUM");
        item.put("vocabulary",List.of());item.put("grammar",List.of());item.put("hints",List.of());item.put("thoughtGroups",List.of());
        item.put("notes",invalidSurface==null ? List.of() : List.of(Map.of("surface",invalidSurface,"text","Ghi chú.")));
        return new AiExecutionResult(UUID.randomUUID(),"MOCK","test",Map.of("structured",Map.of("targetLocale","vi-VN","items",List.of(item))),1,1,10);
    }
}
