package com.lyreo.database;

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
import com.lyreo.lesson.infrastructure.LessonAuthoringConfiguration;
import com.lyreo.lesson.infrastructure.persistence.JdbcLessonPracticeRepository;
import com.lyreo.lexicon.api.LexiconLinkService;
import com.lyreo.platform.jobs.application.*;
import com.lyreo.platform.jobs.infrastructure.JdbcBackgroundJobRepository;
import com.lyreo.platform.storage.ObjectStoragePort;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(classes=LessonAuthoringIT.Config.class)
class LessonAuthoringIT {
    @ServiceConnection static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:18.6-alpine");
    static {postgres.start();}
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @Import(LessonAuthoringConfiguration.class)
    static class Config {
        @Bean AuthoringSourcePort source() {return mock(AuthoringSourcePort.class);}
        @Bean ObjectStoragePort storage() {return mock(ObjectStoragePort.class);}
        @Bean AiInvocationService ai() {return mock(AiInvocationService.class);}
        @Bean LexiconLinkService lexicon() {return mock(LexiconLinkService.class);}
        @Bean BackgroundJobRepository jobRepository(NamedParameterJdbcTemplate jdbc) {return new JdbcBackgroundJobRepository(jdbc);}
        @Bean JobStateTransitionService transitions(BackgroundJobRepository repository,ApplicationEventPublisher events) {return new JobStateTransitionService(repository,events);}
        @Bean BackgroundJobService jobs(BackgroundJobRepository repository,JobStateTransitionService transitions) {return new BackgroundJobService(repository,transitions);}
    }
    @Autowired LessonAuthoringService service;
    @Autowired LessonAuthoringJobHandler handler;
    @Autowired AuthoringJobCommitter committer;
    @Autowired AuthoringRepository repository;
    @Autowired AuthoringSourcePort source;
    @Autowired ObjectStoragePort storage;
    @Autowired AiInvocationService ai;
    @Autowired BackgroundJobService jobs;
    @Autowired BackgroundJobRepository jobRepository;
    @Autowired JobStateTransitionService transitions;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach void setup() {
        reset(ai,source,storage);
        when(source.inspect(anyString())).thenReturn(new AuthoringSourcePort.SourceInfo("abcdefghijk","Reference",10_000,true));
        when(source.prepare(anyString(),anyString(),anyLong(),anyLong())).thenReturn("sources/test/segment.wav");
        when(storage.createDownloadUrl(anyString(),any(Duration.class))).thenReturn(URI.create("http://localhost/test.wav"));
        when(ai.execute(eq(AiCapability.STT),anyString(),anyMap(),anyMap())).thenReturn(result(Map.of(
            "text","We learn. Practice helps.","timestamps",List.of(
                Map.of("text","We","start_ms",0,"end_ms",200),Map.of("text","learn","start_ms",200,"end_ms",500),
                Map.of("text","Practice","start_ms",600,"end_ms",900),Map.of("text","helps","start_ms",900,"end_ms",1200)),
            "segments",List.of(Map.of("text","We learn. Practice helps.","start_ms",0,"end_ms",1200)))));
        when(ai.execute(eq(AiCapability.GENERAL_LLM),anyString(),anyMap(),anyMap())).thenAnswer(invocation->{
            Map<String,Object> input=invocation.getArgument(2);
            List<Map<String,Object>> targets=(List<Map<String,Object>>)input.get("targets");
            var items=targets.stream().map(target->Map.of("sentenceId",target.get("sentenceId"),"translation","Bản dịch tiếng Việt.",
                "dictationPriority","HIGH","shadowingPriority","MEDIUM","vocabulary",List.of(),"grammar",List.of(),
                "hints",List.of(),"notes",List.of(),"thoughtGroups",List.of())).toList();
            return result(Map.of("structured",Map.of("targetLocale","vi-VN","items",items)));
        });
    }
    @Test void runsCheckpointsPublishesAtomicallyAndProtectsPracticeMembership() throws Exception {
        var draft=prepared();
        assertThat(draft.timingStatus).isEqualTo("DRAFT_VALID");
        draft=service.approve(draft.id,draft.revision);
        draft=service.karaoke(draft.id,draft.revision);
        run(service.generate(draft.id,draft.revision));draft=service.get(draft.id);
        assertThat(draft.status).isEqualTo("CONTENT_REVIEW");
        UUID selected=draft.sentences.getFirst().id,excluded=draft.sentences.getLast().id;
        draft=service.candidates(draft.id,draft.revision,List.of(selected),List.of());
        draft=service.publish(draft.id,draft.revision);
        assertThat(draft.status).isEqualTo("PUBLISHED");
        UUID id=draft.id;
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lesson_activity_sentence m JOIN lesson_activity a ON a.id=m.activity_id WHERE a.lesson_id=? AND a.activity_type='DICTATION'",Integer.class,id)).isEqualTo(1);
        assertThatThrownBy(()->jdbc.update("UPDATE lesson_sentence SET text='changed' WHERE id=?",selected)).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("DELETE FROM lesson_activity WHERE lesson_id=?",id)).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("UPDATE lesson SET status='READY' WHERE id=?",id)).hasMessageContaining("immutable");
        assertThatThrownBy(()->service.approve(id,99)).hasMessageContaining("read-only");
        UUID unrelated=UUID.randomUUID();jdbc.update("INSERT INTO lesson(id,title,source_type) VALUES(?,'Other','TEXT')",unrelated);
        assertThatThrownBy(()->jdbc.update("INSERT INTO lesson_annotation(id,lesson_id,sentence_id,annotation_type,payload_json) VALUES(?,?,?,'BAD','{}')",
            UUID.randomUUID(),unrelated,selected)).hasMessageContaining("same lesson");
        var practice=new JdbcLessonPracticeRepository(new NamedParameterJdbcTemplate(jdbc));
        UUID activity=jdbc.queryForObject("SELECT id FROM lesson_activity WHERE lesson_id=? AND activity_type='DICTATION'",UUID.class,id);
        assertThat(practice.findDictationTarget(id,activity,selected)).isPresent();
        assertThat(practice.findDictationTarget(id,activity,excluded)).isEmpty();
        UUID learner=UUID.randomUUID();jdbc.update("INSERT INTO app_user(id,keycloak_subject) VALUES(?,?)",learner,learner.toString());
        var target=practice.findDictationTarget(id,activity,selected).orElseThrow();
        practice.appendDictationAttempt(learner,target,"We learn.",100,"{}");
        assertThat(practice.refreshActivityProgress(learner,id,activity,70).totalItems()).isEqualTo(1);
        assertThat(practice.tryMarkActivityCompleted(learner,activity)).isTrue();
    }
    @Test void failedPublicationRollsBackProjectionAndKeepsEditableDraft() throws Exception {
        var draft=prepared();draft=service.approve(draft.id,draft.revision);draft=service.karaoke(draft.id,draft.revision);
        run(service.generate(draft.id,draft.revision));draft=service.get(draft.id);
        // A database conflict after sentence writes proves the transaction does not partially publish.
        UUID conflicting=draft.sentences.getLast().id;UUID unrelated=UUID.randomUUID();
        jdbc.update("INSERT INTO lesson(id,title,source_type) VALUES(?,'Legacy','TEXT')",unrelated);
        jdbc.update("INSERT INTO lesson_sentence(id,lesson_id,position,text) VALUES(?,?,0,'existing')",conflicting,unrelated);
        UUID id=draft.id;long revision=draft.revision;
        assertThatThrownBy(()->service.publish(id,revision)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lesson_sentence WHERE lesson_id=?",Integer.class,id)).isZero();
        assertThat(service.get(id).publishedAt).isNull();
        assertThat(jdbc.queryForObject("SELECT text FROM lesson_sentence WHERE id=?",String.class,conflicting)).isEqualTo("existing");
    }
    @Test void leaseAndRevisionFencesRejectLateOutputsAndChunkCheckpointSurvivesReload() throws Exception {
        var draft=prepared();draft=service.approve(draft.id,draft.revision);draft=service.karaoke(draft.id,draft.revision);
        var accepted=service.generate(draft.id,draft.revision);claim(accepted.jobId());
        var job=jobs.findById(accepted.jobId()).orElseThrow();
        var input=repository.jobInput(job.id());var output=service.get(draft.id);
        committer.stage(job,0,output.sentences);
        assertThat(repository.chunk(job.id(),0)).isPresent();
        jdbc.update("UPDATE background_job SET lease_owner='replacement' WHERE id=?",job.id());
        assertThatThrownBy(()->committer.commit(job,input,output)).isInstanceOf(JobLeaseLostException.class);
        jdbc.update("UPDATE background_job SET lease_owner='test-worker' WHERE id=?",job.id());
        service.transcript(draft.id,output.revision,List.of(new LessonAuthoringService.SentenceInput(output.sentences.getFirst().id,"We teach.","LEARN")));
        assertThatThrownBy(()->committer.commit(job,input,output)).hasMessageContaining("draft changed");
        assertThat(service.get(draft.id).semanticRevision).isNull();
    }
    @Test void groupedBackwardWhisperIntervalsCanBeExplicitlyReviewedWithoutForcedAlignment() throws Exception {
        when(ai.execute(eq(AiCapability.STT),anyString(),anyMap(),anyMap())).thenReturn(result(Map.of("text","A B C.","timestamps",List.of(
            Map.of("text","A","start_ms",0,"end_ms",100),Map.of("text","B","start_ms",120,"end_ms",200),
            Map.of("text","C","start_ms",90,"end_ms",220)))));
        var draft=prepared();
        assertThat(draft.timingStatus).isEqualTo("DRAFT_VALID");
        assertThat(draft.sentences.getFirst().words).hasSize(1);
        assertThat(draft.sentences.getFirst().issues).contains("GROUPED_TIMING");
        assertThatThrownBy(()->service.generate(draft.id,draft.revision)).hasMessageContaining("approval");
        var approved=service.approve(draft.id,draft.revision);
        assertThatThrownBy(()->service.generate(approved.id,approved.revision)).hasMessageContaining("karaoke");
        service.karaoke(approved.id,approved.revision);
        verify(ai,never()).execute(eq(AiCapability.ALIGNMENT),anyString(),anyMap(),anyMap());
    }
    @Test void expiredLeaseCannotBeRenewedOrCommitAuthoringOutput() throws Exception {
        var accepted=service.create("Expired","https://www.youtube.com/watch?v=abcdefghijk",0,5000,true);
        claim(accepted.jobId());
        var job=jobs.findById(accepted.jobId()).orElseThrow();
        jdbc.update("UPDATE background_job SET lease_until=now()-interval '1 second' WHERE id=?",job.id());
        assertThat(jobRepository.heartbeat(job.id(),job.leaseOwner(),Duration.ofMinutes(1))).isFalse();
        assertThat(jobRepository.updateProgress(job.id(),job.leaseOwner(),"LATE",50)).isFalse();
        assertThat(jobRepository.markSucceeded(job.id(),job.leaseOwner())).isFalse();
        assertThat(jobRepository.scheduleRetry(job.id(),job.leaseOwner(),java.time.Instant.now(),"LATE","late")).isFalse();
        assertThatThrownBy(()->committer.commit(job,repository.jobInput(job.id()),service.get(accepted.lessonId())))
            .isInstanceOf(JobLeaseLostException.class);
        assertThat(service.get(accepted.lessonId()).sentences).isEmpty();
    }
    @Test void legacyInsertWaitingForPublicationCannotModifyThePublishedLesson() throws Exception {
        var draft=prepared();draft=service.approve(draft.id,draft.revision);draft=service.karaoke(draft.id,draft.revision);
        run(service.generate(draft.id,draft.revision));draft=service.get(draft.id);
        UUID id=draft.id;long revision=draft.revision;
        var writerPid=new java.util.concurrent.atomic.AtomicInteger();
        var writerStarted=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var result=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Throwable>>();
            new TransactionTemplate(transactions).executeWithoutResult(status->{
                service.publish(id,revision);
                result.set(executor.submit(()->{
                    try(var connection=java.sql.DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword())) {
                        try(var statement=connection.createStatement();var row=statement.executeQuery("SELECT pg_backend_pid()")) {
                            row.next();writerPid.set(row.getInt(1));
                        }
                        writerStarted.countDown();
                        try(var statement=connection.prepareStatement("INSERT INTO lesson_annotation(id,lesson_id,annotation_type,payload_json) VALUES(?,?,'LEGACY','{}')")) {
                            statement.setObject(1,UUID.randomUUID());statement.setObject(2,id);statement.executeUpdate();
                        }
                        return null;
                    } catch(Throwable failure) {writerStarted.countDown();return failure;}
                }));
                try {
                    assertThat(writerStarted.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                    boolean waiting=false;
                    while(System.nanoTime()<deadline) {
                        waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT wait_event_type='Lock' FROM pg_stat_activity WHERE pid=?",Boolean.class,writerPid.get()));
                        if(waiting)break;
                        Thread.sleep(10);
                    }
                    assertThat(waiting).as("legacy insert waits for publication transaction").isTrue();
                } catch(InterruptedException failure) {Thread.currentThread().interrupt();throw new RuntimeException(failure);}
            });
            assertThat(result.get().get(5,java.util.concurrent.TimeUnit.SECONDS)).hasMessageContaining("immutable");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lesson_annotation WHERE lesson_id=? AND annotation_type='LEGACY'",Integer.class,id)).isZero();
    }
    @Test void lexicalEditUsesEvidenceAnchoredAlignmentAndRequiresNewKaraokeReview() throws Exception {
        var draft=prepared();var first=draft.sentences.getFirst();var second=draft.sentences.getLast();
        draft=service.transcript(draft.id,draft.revision,List.of(new LessonAuthoringService.SentenceInput(first.id,"We teach.","LEARN"),
            new LessonAuthoringService.SentenceInput(second.id,second.text,"WATCH_ONLY")));
        assertThat(draft.timingStatus).isEqualTo("STALE");
        draft=service.approve(draft.id,draft.revision);
        when(ai.execute(eq(AiCapability.ALIGNMENT),anyString(),anyMap(),anyMap())).thenReturn(result(Map.of("words",List.of(
            Map.of("text","We","start_ms",0,"end_ms",200),Map.of("text","teach","start_ms",200,"end_ms",500),
            Map.of("text","Practice","start_ms",600,"end_ms",900),Map.of("text","helps","start_ms",900,"end_ms",1200)))));
        run(service.align(draft.id,draft.revision));draft=service.get(draft.id);
        assertThat(draft.timingStatus).isEqualTo("VALID");assertThat(draft.karaokeReviewedRevision).isNull();
        assertThat(draft.sentences.getFirst().dictationEligible).isTrue();
        assertThat(draft.sentences.getLast().dictationEligible).isFalse();
        var capture=org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(ai).execute(eq(AiCapability.ALIGNMENT),anyString(),capture.capture(),anyMap());
        List<Map<String,Object>> windows=(List<Map<String,Object>>)capture.getValue().get("segments");
        assertThat(windows.getFirst()).containsEntry("start_ms",0L).containsEntry("end_ms",1200L);
    }
    @Test void simulated429WaitsDurablyUntilDueThenResumesPreparationExactlyOnce() {
        var accepted=service.create("Simulated quota resume","https://www.youtube.com/watch?v=abcdefghijk",0,5000,true);
        var before=service.get(accepted.lessonId());
        // Other methods leave queued fixtures; this worker scenario owns only the new job.
        jdbc.update("UPDATE background_job SET status='CANCELLED',cancel_requested_at=now() WHERE id<>? AND "
            +"(status='QUEUED' OR (status='RETRY_WAIT' AND next_retry_at<=now()))",accepted.jobId());
        jdbc.update("UPDATE background_job SET priority=10000 WHERE id=?",accepted.jobId());
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        var transcript=result(Map.of("text","We learn. Practice helps.","timestamps",List.of(
            Map.of("text","We","start_ms",0,"end_ms",200),Map.of("text","learn","start_ms",200,"end_ms",500),
            Map.of("text","Practice","start_ms",600,"end_ms",900),Map.of("text","helps","start_ms",900,"end_ms",1200))));
        doAnswer(call->{
            if(attempts.incrementAndGet()==1) throw new com.lyreo.ai.application.execution.AiExecutionException(
                "Simulated HTTP 429; no live provider billing",false,"PROVIDER_RATE_LIMITED").retryAfter(60);
            return transcript;
        }).when(ai).execute(eq(AiCapability.STT),anyString(),anyMap(),anyMap());
        try(var worker=new com.lyreo.platform.jobs.infrastructure.BackgroundJobWorker(jobRepository,List.of(handler),
            transitions,"quota-worker",Duration.ofMinutes(1),1)) {
            assertThat(worker.runOnce()).isEqualTo(1);
            var waiting=jobs.findById(accepted.jobId()).orElseThrow();
            assertThat(waiting.status().name()).isEqualTo("RETRY_WAIT");
            assertThat(waiting.nextRetryAt()).isAfter(java.time.Instant.now());
            assertThat(jdbc.queryForObject("SELECT error_code FROM background_job WHERE id=?",String.class,accepted.jobId()))
                .isEqualTo("PROVIDER_QUOTA_WAIT");
            assertThat(waiting.attemptCount()).isEqualTo(1);
            var unchanged=service.get(accepted.lessonId());
            assertThat(unchanged.revision).isEqualTo(before.revision);
            assertThat(unchanged.transcriptRevision).isEqualTo(before.transcriptRevision);
            assertThat(unchanged.sentences).isEmpty();
            assertThat(unchanged.audioObjectKey).isNull();
            assertThat(repository.jobInput(accepted.jobId()).completed()).isFalse();
            assertThat(worker.runOnce()).isZero();
            assertThat(attempts.get()).isEqualTo(1);
            jdbc.update("UPDATE background_job SET next_retry_at=now()-interval '1 second' WHERE id=?",accepted.jobId());
            assertThat(worker.runOnce()).isEqualTo(1);
            var finished=jobs.findById(accepted.jobId()).orElseThrow();
            assertThat(finished.status().name()).isEqualTo("SUCCEEDED");
            assertThat(finished.attemptCount()).isEqualTo(2);
            var prepared=service.get(accepted.lessonId());
            assertThat(prepared.sentences).hasSize(2);
            assertThat(prepared.timingStatus).isEqualTo("DRAFT_VALID");
            assertThat(prepared.revision).isEqualTo(before.revision+1);
            assertThat(prepared.transcriptRevision).isEqualTo(before.transcriptRevision+1);
            assertThat(repository.jobInput(accepted.jobId()).completed()).isTrue();
            assertThat(worker.runOnce()).isZero();
            assertThat(service.get(accepted.lessonId()).revision).isEqualTo(prepared.revision);
            verify(ai,times(2)).execute(eq(AiCapability.STT),anyString(),anyMap(),anyMap());
        }
    }

    @Test void invalidSemanticCorrectionDoesNotOverwritePreviouslyValidatedDraft() throws Exception {
        var draft=prepared();draft=service.approve(draft.id,draft.revision);draft=service.karaoke(draft.id,draft.revision);
        run(service.generate(draft.id,draft.revision));draft=service.get(draft.id);
        String previous=draft.sentences.getFirst().translation;
        var accepted=service.generate(draft.id,draft.revision);claim(accepted.jobId());
        var before=service.get(draft.id);
        clearInvocations(ai);
        doReturn(result(Map.of("content","{broken"))).when(ai).execute(eq(AiCapability.GENERAL_LLM),anyString(),anyMap(),anyMap());
        assertThatThrownBy(()->handler.execute(jobs.findById(accepted.jobId()).orElseThrow(),mock(JobExecutionContext.class)))
            .isInstanceOfSatisfying(PermanentJobException.class,failure->assertThat(failure.errorCode()).isEqualTo("AUTHORING_VALIDATION_FAILED"));
        verify(ai,times(2)).execute(eq(AiCapability.GENERAL_LLM),anyString(),anyMap(),anyMap());
        var after=service.get(draft.id);
        assertThat(after.revision).isEqualTo(before.revision);
        assertThat(after.semanticRevision).isEqualTo(before.semanticRevision);
        assertThat(after.sentences.getFirst().translation).isEqualTo(previous);
        assertThat(repository.chunk(accepted.jobId(),0)).isEmpty();
        assertThat(repository.jobInput(accepted.jobId()).completed()).isFalse();
    }
    @Test void cancellationWhileProviderRunsDiscardsOutputAndKeepsDraftCheckpoint() throws Exception {
        var accepted=service.create("Cancel provider","https://www.youtube.com/watch?v=abcdefghijk",0,5000,true);
        claim(accepted.jobId());
        var before=service.get(accepted.lessonId());
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        when(ai.execute(eq(AiCapability.STT),anyString(),anyMap(),anyMap())).thenAnswer(call->{
            entered.countDown();
            assertThat(release.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return result(Map.of("text","Late provider output."));
        });
        var context=mock(JobExecutionContext.class,CALLS_REAL_METHODS);
        when(context.isCancellationRequested()).thenAnswer(call->jobRepository.isCancellationRequested(accepted.jobId()));
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var execution=executor.submit(()->{
                try {handler.execute(jobs.findById(accepted.jobId()).orElseThrow(),context);return null;}
                catch(Exception failure) {return failure;}
            });
            try {
                assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(jobs.cancel(accepted.jobId())).isEqualTo(CancellationResult.ACCEPTED);
            } finally {release.countDown();}
            assertThat(execution.get(5,java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(JobCancelledException.class);
        }
        var after=service.get(before.id);
        assertThat(after.revision).isEqualTo(before.revision);
        assertThat(after.sentences).isEmpty();
        assertThat(after.audioObjectKey).isNull();
        assertThat(repository.jobInput(accepted.jobId()).completed()).isFalse();
    }
    @Test void cancellationAfterProviderBeforeCommitIsFencedEvenWithoutWorkerCancellationCheck() throws Exception {
        var accepted=service.create("Cancel commit","https://www.youtube.com/watch?v=abcdefghijk",0,5000,true);
        claim(accepted.jobId());
        var before=service.get(accepted.lessonId());
        var context=mock(JobExecutionContext.class);
        doAnswer(call->{jobs.cancel(accepted.jobId());return null;}).when(context).heartbeat();
        assertThatThrownBy(()->handler.execute(jobs.findById(accepted.jobId()).orElseThrow(),context))
            .isInstanceOf(JobLeaseLostException.class);
        var after=service.get(before.id);
        assertThat(after.revision).isEqualTo(before.revision);
        assertThat(after.sentences).isEmpty();
        assertThat(after.audioObjectKey).isNull();
        assertThat(repository.jobInput(accepted.jobId()).completed()).isFalse();
        assertThatThrownBy(()->committer.stage(jobs.findById(accepted.jobId()).orElseThrow(),0,after.sentences))
            .isInstanceOf(JobLeaseLostException.class);
        assertThat(repository.chunk(accepted.jobId(),0)).isEmpty();
    }
    @Test void lexicalEditEvidenceSurvivesReloadAndLetsLongSentenceSplitForAlignment() throws Exception {
        when(source.inspect(anyString())).thenReturn(new AuthoringSourcePort.SourceInfo("abcdefghijk","Long",120_000,true));
        when(ai.execute(eq(AiCapability.STT),anyString(),anyMap(),anyMap())).thenReturn(result(Map.of(
            "text","We learn every day and practice together.","words",List.of(
                Map.of("text","We","start_ms",0,"end_ms",10_000),Map.of("text","learn","start_ms",10_000,"end_ms",20_000),
                Map.of("text","every","start_ms",20_000,"end_ms",30_000),Map.of("text","day","start_ms",30_000,"end_ms",60_000),
                Map.of("text","and","start_ms",60_000,"end_ms",70_000),Map.of("text","practice","start_ms",70_000,"end_ms",90_000),
                Map.of("text","together","start_ms",90_000,"end_ms",120_000)))));
        var accepted=service.create("Long edit","https://www.youtube.com/watch?v=abcdefghijk",0,120_000,true);
        run(accepted);var draft=service.get(accepted.lessonId());
        UUID first=draft.sentences.getFirst().id;
        service.transcript(draft.id,draft.revision,List.of(new LessonAuthoringService.SentenceInput(first,
            "We teach every day and practice together.","LEARN")));
        draft=service.get(draft.id);
        assertThat(draft.sentences.getFirst().words).isEmpty();
        service.transcript(draft.id,draft.revision,List.of(new LessonAuthoringService.SentenceInput(first,"We teach every day.","LEARN"),
            new LessonAuthoringService.SentenceInput(null,"And practice together.","LEARN")));
        draft=service.get(draft.id);
        assertThat(LessonAuthoringService.alignmentSegments(draft)).containsExactly(
            Map.of("text","We teach every day.","start_ms",0L,"end_ms",60_000L),
            Map.of("text","And practice together.","start_ms",60_000L,"end_ms",120_000L));
        draft=service.approve(draft.id,draft.revision);
        service.align(draft.id,draft.revision);
    }

    private AuthoringLesson prepared() throws Exception {
        var accepted=service.create("Authoring","https://www.youtube.com/watch?v=abcdefghijk",0,5000,true);
        run(accepted);return service.get(accepted.lessonId());
    }
    private void claim(UUID job) {jdbc.update("UPDATE background_job SET status='RUNNING',lease_owner='test-worker',lease_until=now()+interval '5 minutes' WHERE id=?",job);}
    private void run(LessonAuthoringService.Accepted accepted) throws Exception {
        claim(accepted.jobId());handler.execute(jobs.findById(accepted.jobId()).orElseThrow(),mock(JobExecutionContext.class));
        jdbc.update("UPDATE background_job SET status='SUCCEEDED' WHERE id=?",accepted.jobId());
    }
    private static AiExecutionResult result(Map<String,Object> output) {return new AiExecutionResult(UUID.randomUUID(),"MOCK","mock-v1",output,100,30,10);}
}
