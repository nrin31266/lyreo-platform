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
    private final AuthoringJobCommitter committer=mock(AuthoringJobCommitter.class);
    private final BackgroundJob job=mock(BackgroundJob.class);
    private final JobExecutionContext context=mock(JobExecutionContext.class,CALLS_REAL_METHODS);
    private final ObjectStoragePort storage=mock(ObjectStoragePort.class);
    private final LessonAuthoringJobHandler handler=new LessonAuthoringJobHandler(repository,mock(AuthoringSourcePort.class),
        storage,ai,committer);
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
    }
    @Test void removedOperationsFailPermanentlyWithoutInferenceOrCommit() {
        for(String operation:List.of("SEMANTIC","IPA")) {
            when(repository.jobInput(job.id())).thenReturn(new AuthoringRepository.JobInput(lesson.id,operation,1,false));
            assertThatThrownBy(()->handler.execute(job,context)).isInstanceOfSatisfying(PermanentJobException.class,
                failure->assertThat(failure.errorCode()).isEqualTo("AUTHORING_OPERATION_REMOVED"));
        }
        verifyNoInteractions(ai,committer);
    }
    @Test void unsupportedAlignmentProviderEndsWithConfigurationError() {
        alignment(List.of());
        when(ai.execute(eq(AiCapability.ALIGNMENT),anyString(),anyMap(),anyMap()))
            .thenThrow(new com.lyreo.ai.application.execution.AiExecutionException("private runtime detail",true,"AI_HTTP_501"));
        assertThatThrownBy(()->handler.execute(job,context)).isInstanceOfSatisfying(PermanentJobException.class,
            failure->assertThat(failure.errorCode()).isEqualTo("AI_CONFIGURATION_REQUIRED"));
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
    @Test void retriesOnlyWhollyUnresolvedSentencesOnceAtTheirObservedAnchors() throws Exception {
        List<Map<String,Object>> initial=List.of(Map.of("text","We","start_ms",0,"end_ms",100),
            Map.of("text","learn","start_ms",100,"end_ms",200),
            Map.of("text","English","start_ms",300,"end_ms",300),
            Map.of("text","daily","start_ms",300,"end_ms",300));
        alignment(initial);
        sentence.text="We learn.";sentence.coarseEndMs=200L;
        var next=new AuthoringSentence(UUID.randomUUID(),"English daily.","LEARN");
        next.coarseStartMs=250L;next.coarseEndMs=500L;lesson.sentences=List.of(sentence,next);
        when(ai.execute(eq(AiCapability.ALIGNMENT),anyString(),anyMap(),anyMap()))
            .thenReturn(new AiExecutionResult(UUID.randomUUID(),"QWEN","test",Map.of("words",initial),1,1,10))
            .thenReturn(new AiExecutionResult(UUID.randomUUID(),"QWEN","test",Map.of("words",List.of(
                Map.of("text","English","start_ms",300,"end_ms",350),
                Map.of("text","daily","start_ms",350,"end_ms",450))),1,1,10));
        handler.execute(job,context);
        verify(ai).execute(eq(AiCapability.ALIGNMENT),eq(""),argThat(input->"English daily.".equals(input.get("text"))
            && List.of(Map.of("text","English daily.","start_ms",250L,"end_ms",500L)).equals(input.get("segments"))),anyMap());
        assertThat(sentence.words).extracting(AuthoringSentence.Word::text).containsExactly("We","learn");
        assertThat(next.words).hasSize(2);
        verify(committer).commit(eq(job),any(),eq(lesson));
    }
    @Test void alignmentKeepsTouchingZeroDurationWordsInsideTheirApprovedSentences() throws Exception {
        alignment(List.of(Map.of("text","We","start_ms",0,"end_ms",100),
            Map.of("text","learn","start_ms",100,"end_ms",200),
            Map.of("text","English","start_ms",200,"end_ms",200),
            Map.of("text","daily","start_ms",200,"end_ms",400)));
        sentence.text="We learn.";sentence.coarseEndMs=200L;
        var next=new AuthoringSentence(UUID.randomUUID(),"English daily.","LEARN");
        next.coarseStartMs=200L;next.coarseEndMs=400L;
        lesson.sentences=List.of(sentence,next);
        handler.execute(job,context);
        assertThat(sentence.text).isEqualTo("We learn.");
        assertThat(next.text).isEqualTo("English daily.");
        assertThat(sentence.words).extracting(AuthoringSentence.Word::text).containsExactly("We","learn");
        assertThat(next.words).extracting(AuthoringSentence.Word::text).containsExactly("English daily");
        verify(committer).commit(eq(job),any(),eq(lesson));
    }
    private void alignment(List<Map<String,Object>> words) {
        lesson.sentences=List.of(sentence);lesson.endMs=1000;lesson.audioObjectKey="test.wav";
        sentence.coarseStartMs=0L;sentence.coarseEndMs=1000L;
        when(storage.createDownloadUrl(anyString(),any())).thenReturn(java.net.URI.create("http://storage/test.wav"));
        when(repository.jobInput(job.id())).thenReturn(new AuthoringRepository.JobInput(lesson.id,"ALIGN",1,false));
        when(ai.execute(eq(AiCapability.ALIGNMENT),anyString(),anyMap(),anyMap()))
            .thenReturn(new AiExecutionResult(UUID.randomUUID(),"QWEN","test",Map.of("words",words),1,1,10));
    }
    @Test void alignsEverySentenceIncludingLegacyHiddenText() throws Exception {
        alignment(List.of(Map.of("text","We","start_ms",0,"end_ms",100),
            Map.of("text","learn","start_ms",100,"end_ms",200),
            Map.of("text","English","start_ms",200,"end_ms",500),
            Map.of("text","Continue","start_ms",600,"end_ms",900)));
        sentence.text="We learn.";
        var hidden=new AuthoringSentence(UUID.randomUUID(),"English.","LEARN");
        hidden.coarseStartMs=0L;hidden.coarseEndMs=1000L;
        hidden.hidden=true;
        var following=new AuthoringSentence(UUID.randomUUID(),"Continue.","LEARN");
        following.coarseStartMs=0L;following.coarseEndMs=1000L;
        lesson.sentences=List.of(sentence,hidden,following);
        handler.execute(job,context);
        verify(ai).execute(eq(AiCapability.ALIGNMENT),eq(""),argThat(input->"We learn. English. Continue.".equals(input.get("text"))),anyMap());
        assertThat(lesson.sentences).hasSize(3);
        assertThat(following.words.getFirst().startMs()).isEqualTo(600);
        assertThat(hidden.text).isEqualTo("English.");assertThat(hidden.words).hasSize(1);
        verify(committer).commit(eq(job),any(),eq(lesson));
    }
}
