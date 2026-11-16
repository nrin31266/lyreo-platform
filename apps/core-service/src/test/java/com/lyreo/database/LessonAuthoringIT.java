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
import com.lyreo.lesson.domain.authoring.AuthoringSentence;
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

@SpringBootTest(classes=LessonAuthoringIT.Config.class,properties="lyreo.ai-service.internal-token=test-token")
class LessonAuthoringIT {
    @ServiceConnection static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:18.6-alpine");
    static {postgres.start();}
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @Import({LessonAuthoringConfiguration.class,com.lyreo.lesson.infrastructure.persistence.JdbcLessonCatalog.class,com.lyreo.lesson.infrastructure.persistence.JdbcSourcePreview.class})
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
    @Autowired com.lyreo.lesson.application.port.LessonCatalogPort catalog;
    @Autowired com.lyreo.lesson.infrastructure.persistence.JdbcSourcePreview previews;
    @Autowired LessonAuthoringJobHandler handler;
    @Autowired AuthoringJobCommitter committer;
    @Autowired com.lyreo.lesson.application.port.AuthoringMediaPort media;
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
        service.setReuseDraftTiming(true);
        reset(ai,source,storage);
        when(source.inspect(anyString())).thenReturn(new AuthoringSourcePort.SourceInfo("abcdefghijk","Reference",10_000,true));
        when(source.prepare(anyString(),anyString(),anyLong(),anyLong())).thenReturn("sources/test/segment.wav");
        when(storage.createDownloadUrl(anyString(),any(Duration.class))).thenReturn(URI.create("http://localhost/test.wav"));
        when(ai.execute(eq(AiCapability.STT),anyString(),anyMap(),anyMap())).thenReturn(result(Map.of(
            "text","We learn. Practice helps.","timestamps",List.of(
                Map.of("text","We","start_ms",0,"end_ms",200),Map.of("text","learn","start_ms",200,"end_ms",500),
                Map.of("text","Practice","start_ms",600,"end_ms",900),Map.of("text","helps","start_ms",900,"end_ms",1200)),
            "segments",List.of(Map.of("text","We learn. Practice helps.","start_ms",0,"end_ms",1200)))));

    }
    @Test void legacyOperationsFailWithoutWritingAndNoLongerBlockDraftWork() throws Exception {
        for(String operation:List.of("SEMANTIC","IPA")) {
            var draft=prepared();
            UUID id=jobs.enqueue(LessonAuthoringService.JOB_TYPE,"lesson",draft.id,50,3,"{}");
            repository.addJob(draft.id,id,operation,draft.transcriptRevision);
            draft.activeJobId=id;repository.save(draft);claim(id);
            var job=jobs.findById(id).orElseThrow();var before=service.get(draft.id);
            assertThatThrownBy(()->handler.execute(job,mock(JobExecutionContext.class)))
                .isInstanceOfSatisfying(PermanentJobException.class,error->assertThat(error.errorCode()).isEqualTo("AUTHORING_OPERATION_REMOVED"));
            assertThatThrownBy(()->committer.commit(job,repository.jobInput(id),before))
                .isInstanceOfSatisfying(PermanentJobException.class,error->assertThat(error.errorCode()).isEqualTo("AUTHORING_OPERATION_REMOVED"));
            assertThat(service.get(draft.id).revision).isEqualTo(before.revision);
            assertThat(transitions.failed(job,"test-worker","AUTHORING_OPERATION_REMOVED","Operation removed",job.currentStep(),job.progressPercent())).isTrue();
            var approved=service.approve(draft.id,before.revision);
            assertThat(service.align(draft.id,approved.revision).jobId()).isNotEqualTo(id);
        }
    }
    @Test void deletionRejectsActiveJobsAndPreservesSharedSources() throws Exception {
        var accepted=service.create("Delete draft","https://www.youtube.com/watch?v=abcdefghijk",0,2000);
        assertThatThrownBy(()->catalog.deleteDraft(accepted.lessonId())).hasMessageContaining("Cancel active jobs");
        jobs.cancel(accepted.jobId());
        var category=catalog.saveCategory(UUID.randomUUID(),"Deleted draft "+UUID.randomUUID());
        catalog.update(accepted.lessonId(),null,List.of(category.id()));
        catalog.deleteDraft(accepted.lessonId());
        catalog.deleteCategory(category.id());
        assertThatThrownBy(()->service.get(accepted.lessonId())).hasMessageContaining("not found");
        assertThatThrownBy(()->catalog.get(accepted.lessonId())).hasMessageContaining("not found");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lesson WHERE id=?",Long.class,accepted.lessonId())).isEqualTo(1);
    }
    @Test void catalogFiltersAndCategoryChangesPreserveDraftRevision() throws Exception {
        var draft=prepared();String marker="Catalog "+UUID.randomUUID();
        var one=catalog.saveCategory(UUID.randomUUID(),"Topic "+UUID.randomUUID());
        var two=catalog.saveCategory(UUID.randomUUID(),"Other "+UUID.randomUUID());
        catalog.update(draft.id,marker,List.of(one.id(),two.id()));
        var page=catalog.list(marker,"",one.id(),"",0,1);
        assertThat(page.total()).isEqualTo(1);assertThat(page.items().getFirst().categories()).hasSize(2);
        assertThat(service.get(draft.id).revision).isEqualTo(draft.revision);
        assertThatThrownBy(()->catalog.deleteCategory(one.id())).hasMessageContaining("assignments");
        assertThatThrownBy(()->catalog.update(draft.id,null,List.of(UUID.randomUUID()))).hasMessageContaining("Unknown category");
        assertThat(catalog.list(marker,"",one.id(),"",0,20).items().getFirst().categories()).hasSize(2);
        catalog.update(draft.id,null,List.of());catalog.deleteCategory(one.id());catalog.deleteCategory(two.id());
        assertThat(catalog.list(marker,"",one.id(),"",0,20).total()).isZero();
        assertThatThrownBy(()->catalog.list("","",null,"",-1,20)).hasMessageContaining("Invalid");
    }
    @Test void previewRequiresExplicitConfirmationAndFencesCancelledOutput() throws Exception {
        verify(source,never()).prepare(anyString(),anyString(),anyLong(),anyLong());
        jdbc.update("INSERT INTO lesson_youtube_source(video_id,title,duration_ms,embed_allowed) VALUES('abcdefghijk','Fixture',10000,true) ON CONFLICT DO NOTHING");
        var preview=previews.create("source",6000,7000);
        assertThat(preview.peaks()).isEmpty();assertThat(preview.startMs()).isEqualTo(6000);
        assertThat(previews.create("source",6000,7000).id()).isEqualTo(preview.id());
        verify(source,never()).prepare(anyString(),anyString(),anyLong(),anyLong());
        assertThatThrownBy(()->previews.audio(preview.id())).hasMessageContaining("not ready");
        claim(preview.jobId());
        byte[] pcm=new byte[32000];for(int i=0;i<pcm.length;i+=2) {pcm[i]=0;pcm[i+1]=64;}
        var format=new javax.sound.sampled.AudioFormat(16000,16,1,true,false);
        var bytes=new java.io.ByteArrayOutputStream();
        javax.sound.sampled.AudioSystem.write(new javax.sound.sampled.AudioInputStream(new java.io.ByteArrayInputStream(pcm),format,16000),javax.sound.sampled.AudioFileFormat.Type.WAVE,bytes);
        when(storage.get(anyString())).thenAnswer(call->new java.io.ByteArrayInputStream(bytes.toByteArray()));
        previews.execute(jobs.findById(preview.jobId()).orElseThrow(),mock(JobExecutionContext.class));
        assertThat(previews.get(preview.id()).peaks()).isNotEmpty().allSatisfy(value->assertThat(value).isEqualTo(0.5));
        assertThat(previews.audio(preview.id())).isEqualTo(bytes.toByteArray());
        assertThat(previews.get(preview.id()).audioDurationMs()).isEqualTo(1000.0);
        var cancelled=previews.create("source",7000,8000);claim(cancelled.jobId());
        jdbc.update("UPDATE background_job SET status='CANCEL_REQUESTED',cancel_requested_at=now() WHERE id=?",cancelled.jobId());
        assertThatThrownBy(()->previews.execute(jobs.findById(cancelled.jobId()).orElseThrow(),mock(JobExecutionContext.class))).isInstanceOf(JobLeaseLostException.class);
        assertThat(previews.get(cancelled.id()).peaks()).isEmpty();
    }
    @Test void runsCheckpointsPublishesAtomicallyAndProtectsPracticeMembership() throws Exception {
        var draft=prepared();
        assertThat(draft.timingStatus).isEqualTo("DRAFT_VALID");
        draft=service.approve(draft.id,draft.revision);
        draft=service.karaoke(draft.id,draft.revision);
        UUID selected=draft.sentences.getFirst().id,excluded=draft.sentences.getLast().id;
        draft.sentences.forEach(row->row.dictationSelected=row.id.equals(selected));
        draft=legacyPublished(draft);
        assertThat(draft.status).isEqualTo("PUBLISHED");
        UUID id=draft.id;
        long revision=draft.revision;var category=catalog.saveCategory(UUID.randomUUID(),"Published "+UUID.randomUUID());
        catalog.update(id,null,List.of(category.id()));
        assertThat(service.get(id).revision).isEqualTo(revision);
        catalog.updateMetadata(id,"Changed","Description","https://example.com/image.jpg",List.of(category.id()));
        assertThat(catalog.get(id).title()).isEqualTo("Changed");
        assertThat(service.get(id).revision).isEqualTo(revision);
        catalog.visibility(id,false);
        assertThat(catalog.get(id).visible()).isFalse();
        assertThatThrownBy(()->catalog.deleteDraft(id)).hasMessageContaining("Unlist");
        catalog.visibility(id,true);
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
        catalog.visibility(id,false);
        assertThat(practice.findDictationTarget(id,activity,selected)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lesson_practice_attempt WHERE lesson_id=?",Long.class,id)).isEqualTo(1);
        catalog.visibility(id,true);
        assertThat(practice.findDictationTarget(id,activity,selected)).isPresent();
    }
    @Test void leaseAndRevisionFencesRejectLateAlignmentOutputs() throws Exception {
        var draft=prepared();draft=service.approve(draft.id,draft.revision);draft=service.karaoke(draft.id,draft.revision);
        var accepted=service.align(draft.id,draft.revision);claim(accepted.jobId());
        var job=jobs.findById(accepted.jobId()).orElseThrow();
        var input=repository.jobInput(job.id());var output=service.get(draft.id);
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
        var approved=service.approve(draft.id,draft.revision);
        service.karaoke(approved.id,approved.revision);
        verify(ai,never()).execute(eq(AiCapability.ALIGNMENT),anyString(),anyMap(),anyMap());
    }
    @Test void expiredLeaseCannotBeRenewedOrCommitAuthoringOutput() throws Exception {
        var accepted=service.create("Expired","https://www.youtube.com/watch?v=abcdefghijk",0,5000);
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
        UUID id=draft.id;long revision=draft.revision;
        var writerPid=new java.util.concurrent.atomic.AtomicInteger();
        var writerStarted=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var result=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Throwable>>();
            new TransactionTemplate(transactions).executeWithoutResult(status->{
                legacyPublished(service.get(id));
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
    @Test void legacyHiddenDraftRequiresFullAlignmentWithoutWritingOnRead() throws Exception {
        var draft=prepared();var second=draft.sentences.getLast();second.hidden=true;
        draft.approvedRevision=draft.transcriptRevision;draft.karaokeReviewedRevision=draft.transcriptRevision;
        repository.save(draft);long revision=draft.revision;
        var read=service.get(draft.id);
        assertThat(read.alignmentRequired).isTrue();assertThat(read.sentences.getLast().hidden).isTrue();
        assertThat(service.get(draft.id).revision).isEqualTo(revision);
        assertThatThrownBy(()->service.karaoke(read.id,read.revision)).hasMessageContaining("complete transcript");
        var audio=read.audioObjectKey;
        var first=read.sentences.getFirst();
        var saved=service.transcript(read.id,read.revision,List.of(
            new LessonAuthoringService.SentenceInput(first.id,first.text,null),
            new LessonAuthoringService.SentenceInput(second.id,second.text,null,true)));
        assertThat(saved.sentences.getLast().hidden).isFalse();assertThat(saved.timingStatus).isEqualTo("STALE");
        assertThat(saved.audioObjectKey).isEqualTo(audio);assertThat(saved.karaokeReviewedRevision).isNull();
        assertThat(saved.approvedRevision).isNull();
    }
    @Test void checkpointAndFollowupRollBackTogetherAndConcurrentRecoveryQueuesOnlyOnce() throws Exception {
        service.setReuseDraftTiming(false);
        var accepted=service.create("Atomic","https://www.youtube.com/watch?v=abcdefghijk",0,5000);
        claim(accepted.jobId());var job=jobs.findById(accepted.jobId()).orElseThrow();
        var input=repository.jobInput(job.id());var output=service.get(accepted.lessonId());
        output.audioObjectKey="checkpoint.wav";output.sentences=com.lyreo.lesson.domain.authoring.AuthoringText.split("We learn.");
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{
            committer.commit(job,input,output);throw new IllegalStateException("rollback");
        })).hasMessageContaining("rollback");
        assertThat(repository.jobInput(job.id()).completed()).isFalse();
        assertThat(service.get(output.id).sentences).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lesson_authoring_job WHERE lesson_id=?",Long.class,output.id)).isEqualTo(1L);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var commits=new ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<2;i++) commits.add(executor.submit(()->{try {start.await();}catch(InterruptedException error){throw new RuntimeException(error);}committer.commit(job,input,output);}));
            start.countDown();for(var commit:commits) commit.get(5,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(repository.jobInput(job.id()).completed()).isTrue();
        var saved=service.get(output.id);
        assertThat(saved.audioObjectKey).isEqualTo("checkpoint.wav");assertThat(saved.sentences).hasSize(1);
        assertThat(saved.approvedRevision).isNull();assertThat(saved.karaokeReviewedRevision).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lesson_authoring_job WHERE lesson_id=? AND operation='ALIGN'",Long.class,output.id)).isEqualTo(1L);
    }
    @Test void initialAlignmentIsAtomicRecoverableAndNeverApprovesTranscript() throws Exception {
        service.setReuseDraftTiming(false);
        var accepted=service.create("Automatic alignment","https://www.youtube.com/watch?v=abcdefghijk",0,5000);
        run(accepted);var checkpoint=service.get(accepted.lessonId());
        UUID alignment=checkpoint.activeJobId;
        assertThat(alignment).isNotNull().isNotEqualTo(accepted.jobId());
        assertThat(repository.jobInput(alignment).operation()).isEqualTo("ALIGN");
        assertThat(checkpoint.approvedRevision).isNull();assertThat(checkpoint.karaokeReviewedRevision).isNull();
        // Recovery after the business commit must not enqueue or invoke STT again.
        handler.execute(jobs.findById(accepted.jobId()).orElseThrow(),mock(JobExecutionContext.class));
        assertThat(service.get(checkpoint.id).activeJobId).isEqualTo(alignment);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lesson_authoring_job WHERE lesson_id=?",Long.class,checkpoint.id)).isEqualTo(2L);
        claim(alignment);var job=jobs.findById(alignment).orElseThrow();
        when(ai.execute(eq(AiCapability.ALIGNMENT),anyString(),anyMap(),anyMap())).thenThrow(new PermanentJobException("TEST","alignment failed"));
        assertThatThrownBy(()->handler.execute(job,mock(JobExecutionContext.class))).hasMessageContaining("alignment failed");
        transitions.failed(job,"test-worker","TEST","alignment failed",null,0);
        var failed=service.get(checkpoint.id);
        assertThat(failed.audioObjectKey).isEqualTo(checkpoint.audioObjectKey);assertThat(failed.sentences).hasSize(2);
        var retry=service.align(failed.id,failed.revision);
        jobs.cancel(retry.jobId());
        assertThat(service.get(failed.id).audioObjectKey).isEqualTo(checkpoint.audioObjectKey);
        when(ai.execute(eq(AiCapability.ALIGNMENT),anyString(),anyMap(),anyMap())).thenReturn(result(Map.of("words",List.of(
            Map.of("text","We","start_ms",0,"end_ms",200),Map.of("text","learn","start_ms",200,"end_ms",500),
            Map.of("text","Practice","start_ms",600,"end_ms",900),Map.of("text","helps","start_ms",900,"end_ms",1200)))));
        failed=service.get(failed.id);
        run(service.align(failed.id,failed.revision));var aligned=service.get(failed.id);
        assertThat(aligned.timingStatus).isEqualTo("VALID");assertThat(aligned.approvedRevision).isNull();
        assertThat(aligned.karaokeReviewedRevision).isNull();
        verify(ai,times(1)).execute(eq(AiCapability.STT),anyString(),anyMap(),anyMap());
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
        assertThat(draft.sentences).allSatisfy(row->assertThat(row.words).isNotEmpty());
        var capture=org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(ai).execute(eq(AiCapability.ALIGNMENT),anyString(),capture.capture(),anyMap());
        List<Map<String,Object>> windows=(List<Map<String,Object>>)capture.getValue().get("segments");
        assertThat(windows.getFirst()).containsEntry("start_ms",0L).containsEntry("end_ms",1200L);
    }
    @Test void simulated429WaitsDurablyUntilDueThenResumesPreparationExactlyOnce() {
        var accepted=service.create("Simulated quota resume","https://www.youtube.com/watch?v=abcdefghijk",0,5000);
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

    @Test void cancellationWhileProviderRunsDiscardsOutputAndKeepsDraftCheckpoint() throws Exception {
        var accepted=service.create("Cancel provider","https://www.youtube.com/watch?v=abcdefghijk",0,5000);
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
        var accepted=service.create("Cancel commit","https://www.youtube.com/watch?v=abcdefghijk",0,5000);
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
    }
    @Test void lexicalEditEvidenceSurvivesReloadAndLetsLongSentenceSplitForAlignment() throws Exception {
        when(source.inspect(anyString())).thenReturn(new AuthoringSourcePort.SourceInfo("abcdefghijk","Long",120_000,true));
        when(ai.execute(eq(AiCapability.STT),anyString(),anyMap(),anyMap())).thenReturn(result(Map.of(
            "text","We learn every day and practice together.","words",List.of(
                Map.of("text","We","start_ms",0,"end_ms",10_000),Map.of("text","learn","start_ms",10_000,"end_ms",20_000),
                Map.of("text","every","start_ms",20_000,"end_ms",30_000),Map.of("text","day","start_ms",30_000,"end_ms",60_000),
                Map.of("text","and","start_ms",60_000,"end_ms",70_000),Map.of("text","practice","start_ms",70_000,"end_ms",90_000),
                Map.of("text","together","start_ms",90_000,"end_ms",120_000)))));
        var accepted=service.create("Long edit","https://www.youtube.com/watch?v=abcdefghijk",0,120_000);
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

    @Test void manualTimingReopensKaraoke() throws Exception {
        var draft=prepared();draft=service.approve(draft.id,draft.revision);draft=service.karaoke(draft.id,draft.revision);
        var sentence=draft.sentences.getFirst();var changed=new ArrayList<>(sentence.words);var first=changed.getFirst();
        changed.set(0,new AuthoringSentence.Word(first.text(),first.startMs(),first.endMs()-10,first.charStart(),first.charEnd(),first.tokenStart(),first.tokenEnd()));
        draft=service.timing(draft.id,draft.revision,sentence.id,changed);
        assertThat(draft.karaokeReviewedRevision).isNull();assertThat(service.get(draft.id).sentences.getFirst().timingSource).isEqualTo("MANUAL");
        var invalid=new ArrayList<>(changed);var word=invalid.getFirst();invalid.set(0,new AuthoringSentence.Word(word.text(),0,9000,word.charStart(),word.charEnd(),word.tokenStart(),word.tokenEnd()));
        var current=draft;assertThatThrownBy(()->service.timing(current.id,current.revision,sentence.id,invalid)).isInstanceOf(com.lyreo.lesson.domain.authoring.TimingValidationException.class)
            .extracting(error -> ((com.lyreo.lesson.domain.authoring.TimingValidationException)error).code()).isEqualTo("TIMING_OUT_OF_BOUNDS");
        assertThat(service.get(draft.id).revision).isEqualTo(draft.revision);
    }
    @Test void textUsesChosenVoiceAndStopsAtReviewedDraft() throws Exception {
        var draft=service.createText("Text lesson","We learn. Practice helps.","bf_emma");
        UUID id=draft.id;
        assertThat(service.get(id).sourceType).isEqualTo("TEXT");
        assertThat(service.get(id).endMs).isZero();
        assertThat(service.get(id).sentences).hasSize(2);
        when(ai.executeForProvider(eq(AiCapability.TTS),eq("LOCAL_KOKORO"),anyString(),anyMap(),anyMap())).thenReturn(result(Map.of(
            "audio_base64",java.util.Base64.getEncoder().encodeToString(wav(2)),
            "segments",List.of(Map.of("start_ms",0,"end_ms",1000),Map.of("start_ms",1000,"end_ms",2000)))));
        run(service.synthesize(id,draft.revision));draft=service.get(id);
        assertThat(draft.endMs).isEqualTo(2000);assertThat(draft.audioObjectKey).contains("/tts/");
        verify(ai,never()).execute(eq(AiCapability.STT),anyString(),anyMap(),anyMap());
        assertThat(draft.approvedRevision).isNull();
        UUID initialAlign=draft.activeJobId;claim(initialAlign);
        var failedJob=jobs.findById(initialAlign).orElseThrow();
        when(ai.execute(eq(AiCapability.ALIGNMENT),anyString(),anyMap(),anyMap())).thenThrow(new PermanentJobException("TEST","alignment failed"));
        assertThatThrownBy(()->handler.execute(failedJob,mock(JobExecutionContext.class))).hasMessageContaining("alignment failed");
        transitions.failed(failedJob,"test-worker","TEST","alignment failed",null,0);
        assertThat(service.get(id).audioObjectKey).isEqualTo(draft.audioObjectKey);
        assertThat(service.get(id).sentences).hasSize(2);
        when(ai.execute(eq(AiCapability.ALIGNMENT),anyString(),anyMap(),anyMap())).thenReturn(result(Map.of("words",List.of(
            Map.of("text","We","start_ms",0,"end_ms",200),Map.of("text","learn","start_ms",200,"end_ms",500),
            Map.of("text","Practice","start_ms",1000,"end_ms",1300),Map.of("text","helps","start_ms",1300,"end_ms",1600)))));
        run(service.align(id,draft.revision));draft=service.get(id);
        assertThat(draft.approvedRevision).isNull();
        draft=service.approve(id,draft.revision);draft=service.karaoke(id,draft.revision);
        assertThat(service.get(id).karaokeReviewedRevision).isEqualTo(draft.transcriptRevision);
        assertThat(service.get(id).publishedAt).isNull();
        assertThat(catalog.get(id).sourceType()).isEqualTo("TEXT");
        assertThat(catalog.get(id).channelId()).isNull();assertThat(catalog.get(id).effectiveVisible()).isFalse();
    }
    @Test void lateSpeechForOldVoiceCannotCommitOrRetainItsPrivateObject() throws Exception {
        var draft=service.createText("Changed voice","We learn.","af_heart");
        var accepted=service.synthesize(draft.id,draft.revision);
        byte[] bytes=wav(1);
        when(ai.executeForProvider(eq(AiCapability.TTS),eq("LOCAL_KOKORO"),anyString(),anyMap(),anyMap())).thenAnswer(invocation->{
            var current=service.get(draft.id);service.voice(draft.id,current.revision,"bf_emma");
            return result(Map.of("audio_base64",java.util.Base64.getEncoder().encodeToString(bytes),"segments",List.of(Map.of("start_ms",0,"end_ms",1000))));
        });
        claim(accepted.jobId());
        assertThatThrownBy(()->handler.execute(jobs.findById(accepted.jobId()).orElseThrow(),mock(JobExecutionContext.class)))
            .isInstanceOf(PermanentJobException.class).hasMessageContaining("draft changed");
        assertThat(service.get(draft.id).audioObjectKey).isNull();
        assertThat(service.get(draft.id).ttsVoice).isEqualTo("bf_emma");
        verify(storage).delete(endsWith("/"+accepted.jobId()+".wav"));
    }
    @Test void audioUploadSurvivesReloadAndPreparesOnlySelectedRange() throws Exception {
        byte[] bytes=wav(2);
        when(storage.get(anyString())).thenAnswer(invocation->new java.io.ByteArrayInputStream(bytes));
        var source=media.upload("sample.wav",bytes);
        assertThat(media.source(source.id()).durationMs()).isEqualTo(2000);
        var accepted=service.createAudio("Audio lesson",source.id(),source.durationMs(),0,2000);
        run(accepted);var draft=service.get(accepted.lessonId());
        assertThat(draft.sourceType).isEqualTo("AUDIO");assertThat(draft.videoId).isNull();
        assertThat(draft.audioSourceId).isEqualTo(source.id());assertThat(draft.sentences).hasSize(2);
        assertThat(draft.audioObjectKey).contains("/0-2000.wav");
        assertThat(catalog.get(draft.id).channelId()).isNull();
    }
    @Test void channelGateRestoresOnlyIndividuallyVisibleLessonsAndCoversNewDrafts() throws Exception {
        String channel="UC"+UUID.randomUUID().toString().replace("-","").substring(0,22);
        jdbc.update("INSERT INTO lesson_youtube_channel(channel_id,title) VALUES(?,'Gate channel')",channel);
        jdbc.update("INSERT INTO lesson_youtube_source(video_id,title,duration_ms,embed_allowed,channel_id,channel_title) VALUES('abcdefghijk','Source',10000,true,?,'Gate channel') ON CONFLICT(video_id) DO UPDATE SET channel_id=excluded.channel_id",channel);
        var first=prepared();first=service.approve(first.id,first.revision);first=service.karaoke(first.id,first.revision);
        first=legacyPublished(first);
        catalog.channelVisibility(channel,false);
        assertThat(catalog.get(first.id).visible()).isTrue();assertThat(catalog.get(first.id).effectiveVisible()).isFalse();
        var next=prepared();assertThat(catalog.get(next.id).channelVisible()).isFalse();
        UUID activity=jdbc.queryForObject("SELECT id FROM lesson_activity WHERE lesson_id=? AND activity_type='DICTATION'",UUID.class,first.id);
        var practice=new JdbcLessonPracticeRepository(new NamedParameterJdbcTemplate(jdbc));
        assertThat(practice.findDictationTarget(first.id,activity,first.sentences.getFirst().id)).isEmpty();
        catalog.visibility(first.id,false);catalog.channelVisibility(channel,true);
        assertThat(catalog.get(first.id).effectiveVisible()).isFalse();
        catalog.visibility(first.id,true);assertThat(catalog.get(first.id).effectiveVisible()).isTrue();
        assertThat(practice.findDictationTarget(first.id,activity,first.sentences.getFirst().id)).isPresent();
    }
    @Test void automaticAudioTitleIsSetOnceAndManualRenameWinsDuringStt() throws Exception {
        byte[] bytes=wav(2);
        when(storage.get(anyString())).thenAnswer(call->new java.io.ByteArrayInputStream(bytes));
        var uploaded=media.upload("lesson.wav",bytes);
        assertThat(media.source(uploaded.id()).originalFilename()).isEqualTo("lesson.wav");
        var first=service.createAudio(null,uploaded.id(),2000,0,2000,"lesson.wav");
        assertThat(catalog.get(first.lessonId()).title()).isEqualTo("lesson.wav");
        catalog.updateMetadata(first.lessonId(),"lesson.wav","Description",null,List.of());
        assertThat(service.get(first.lessonId()).automaticTitle).isTrue();
        run(first);
        assertThat(catalog.get(first.lessonId()).title()).isEqualTo("We learn.");
        assertThat(service.get(first.lessonId()).automaticTitle).isFalse();
        var second=service.createAudio("",uploaded.id(),2000,0,2000,"lesson.wav");
        when(ai.execute(eq(AiCapability.STT),anyString(),anyMap(),anyMap())).thenAnswer(call->{
            catalog.updateMetadata(second.lessonId(),"My title","",null,List.of());
            return result(Map.of("text","A late transcript.","segments",List.of(Map.of("text","A late transcript.","start_ms",0,"end_ms",1500))));
        });
        run(second);
        assertThat(catalog.get(second.lessonId()).title()).isEqualTo("My title");
        assertThat(service.get(second.lessonId()).automaticTitle).isFalse();
    }
    @Test void categoryTranslationsAreValidatedAndPreservedByLegacyUpdates() {
        UUID id=UUID.randomUUID();
        var category=catalog.saveCategory(id,"Travel",Map.of("vi","Du lịch"));
        assertThat(category.translations()).containsEntry("vi","Du lịch");
        assertThat(catalog.saveCategory(id,"Trips").translations()).containsEntry("vi","Du lịch");
        assertThatThrownBy(()->catalog.saveCategory(id,"Travel",Map.of("../bad","bad"))).hasMessageContaining("locale");
        catalog.deleteCategory(id);
    }
    @Test void voiceSamplesResumeAfterFailureAndNeverSynthesizeOnListen() throws Exception {
        var voiceMedia=mock(com.lyreo.lesson.application.port.AuthoringMediaPort.class);
        when(voiceMedia.voices()).thenReturn(new com.lyreo.lesson.application.port.AuthoringMediaPort.Voices("af_heart",List.of(
            new com.lyreo.lesson.application.port.AuthoringMediaPort.Voice("af_heart","US","Heart"),
            new com.lyreo.lesson.application.port.AuthoringMediaPort.Voice("bm_george","UK","George"))));
        Map<String,byte[]> objects=new java.util.HashMap<>();
        when(storage.exists(anyString())).thenAnswer(call->objects.containsKey(call.getArgument(0)));
        when(storage.get(anyString())).thenAnswer(call->new java.io.ByteArrayInputStream(objects.get(call.getArgument(0))));
        doAnswer(call->{objects.put(call.getArgument(0),call.getArgument(2));return null;}).when(storage).put(anyString(),anyString(),any());
        var library=new com.lyreo.lesson.infrastructure.integration.VoiceSampleLibrary(voiceMedia,storage,ai,jobs,
            new NamedParameterJdbcTemplate(jdbc),transactions,new tools.jackson.databind.ObjectMapper());
        byte[] audio=wav(1);audio[100]=40;
        when(ai.executeForProvider(eq(AiCapability.TTS),eq("LOCAL_KOKORO"),eq(""),anyMap(),eq(Map.of("voice","af_heart","speed",1.0))))
            .thenReturn(result(Map.of("audio_base64",java.util.Base64.getEncoder().encodeToString(audio))));
        when(ai.executeForProvider(eq(AiCapability.TTS),eq("LOCAL_KOKORO"),eq(""),anyMap(),eq(Map.of("voice","bm_george","speed",1.0))))
            .thenThrow(new IllegalStateException("temporary failure"));
        UUID id=library.prepare();assertThat(library.prepare()).isEqualTo(id);claim(id);
        var job=jobs.findById(id).orElseThrow();
        assertThatThrownBy(()->library.execute(job,mock(JobExecutionContext.class))).hasMessageContaining("temporary failure");
        assertThat(library.audio("af_heart")).isEqualTo(audio);
        assertThatThrownBy(()->library.audio("bm_george")).hasMessageContaining("not ready");
        when(ai.executeForProvider(eq(AiCapability.TTS),eq("LOCAL_KOKORO"),eq(""),anyMap(),eq(Map.of("voice","bm_george","speed",1.0))))
            .thenReturn(result(Map.of("audio_base64",java.util.Base64.getEncoder().encodeToString(audio))));
        library.execute(job,mock(JobExecutionContext.class));
        assertThat(library.catalog().voices()).allMatch(voice->voice.sampleAvailable());
        verify(ai,times(1)).executeForProvider(eq(AiCapability.TTS),eq("LOCAL_KOKORO"),eq(""),
            eq(Map.of("text",com.lyreo.lesson.infrastructure.integration.VoiceSampleLibrary.TEXT)),eq(Map.of("voice","af_heart","speed",1.0)));
        var cancelled=mock(JobExecutionContext.class);
        doThrow(new com.lyreo.platform.jobs.application.JobCancelledException("cancelled")).when(cancelled).throwIfCancellationRequested();
        assertThatThrownBy(()->library.execute(job,cancelled)).hasMessageContaining("cancelled");
        audio[100]=0;objects.replaceAll((key,bytes)->key.endsWith(".wav") ? audio : bytes);
        assertThatThrownBy(()->library.audio("af_heart")).hasMessageContaining("regenerated");
        jdbc.update("UPDATE background_job SET status='SUCCEEDED' WHERE id=?",id);
    }
    private AuthoringLesson legacyPublished(AuthoringLesson lesson) {
        return new TransactionTemplate(transactions).execute(status->{
            lesson.sentences.getFirst().dictationSelected=true;
            projectLegacyPublished(lesson);
            lesson.status="PUBLISHED";lesson.publishedAt=java.time.Instant.now();repository.save(lesson);
            return lesson;
        });
    }
    private void projectLegacyPublished(AuthoringLesson lesson) {
        var jdbc=new NamedParameterJdbcTemplate(this.jdbc);
        var mapper=new tools.jackson.databind.ObjectMapper();
        int position=0;
        for(var sentence:lesson.sentences) {
            if("SKIP".equals(sentence.usage)) continue;
            jdbc.update("""
                INSERT INTO lesson_sentence(id,lesson_id,position,text,audio_start_ms,audio_end_ms)
                VALUES(:id,:lesson,:position,:text,:start,:end)
                """,Map.of("id",sentence.id,"lesson",lesson.id,"position",position++,"text",sentence.text,
                    "start",sentence.words.getFirst().startMs(),"end",sentence.words.getLast().endMs()));
            int wordIndex=0;
            for(var word:sentence.words) jdbc.update("""
                INSERT INTO lesson_word_timestamp(id,sentence_id,position,surface_text,start_ms,end_ms)
                VALUES(:id,:sentence,:position,:text,:start,:end)
                """,Map.of("id",UUID.randomUUID(),"sentence",sentence.id,"position",wordIndex++,"text",word.text(),
                    "start",word.startMs(),"end",word.endMs()));
            if("LEARN".equals(sentence.usage)) jdbc.update("""
                INSERT INTO lesson_annotation(id,lesson_id,sentence_id,annotation_type,payload_json,generated_by)
                VALUES(:id,:lesson,:sentence,'AUTHORING_SEMANTIC',CAST(:payload AS jsonb),'AI_REVIEWED')
                """,Map.of("id",UUID.randomUUID(),"lesson",lesson.id,"sentence",sentence.id,"payload",mapper.writeValueAsString(sentence)));
        }
        int activityPosition=0;
        for(String type:List.of("WATCH","DICTATION","SHADOWING")) {
            var selected=lesson.sentences.stream().filter(s->!"SKIP".equals(s.usage))
                .filter(s->type.equals("WATCH") || (type.equals("DICTATION") ? s.dictationSelected : s.shadowingSelected)).toList();
            if(selected.isEmpty()) continue;
            UUID activity=UUID.randomUUID();
            jdbc.update("""
                INSERT INTO lesson_activity(id,lesson_id,activity_type,position) VALUES(:id,:lesson,:type,:position)
                """,Map.of("id",activity,"lesson",lesson.id,"type",type,"position",activityPosition++));
            for(var s:selected) jdbc.update("INSERT INTO lesson_activity_sentence(activity_id,sentence_id) VALUES(:activity,:sentence)",
                Map.of("activity",activity,"sentence",s.id));
        }
    }
    private static byte[] wav(int seconds) throws Exception {
        var bytes=new java.io.ByteArrayOutputStream();
        var format=new javax.sound.sampled.AudioFormat(16000,16,1,true,false);
        javax.sound.sampled.AudioSystem.write(new javax.sound.sampled.AudioInputStream(new java.io.ByteArrayInputStream(new byte[seconds*32000]),format,seconds*16000L),javax.sound.sampled.AudioFileFormat.Type.WAVE,bytes);
        return bytes.toByteArray();
    }
    private AuthoringLesson prepared() throws Exception {
        var accepted=service.create("Authoring","https://www.youtube.com/watch?v=abcdefghijk",0,5000);
        run(accepted);return service.get(accepted.lessonId());
    }
    private void claim(UUID job) {jdbc.update("UPDATE background_job SET status='RUNNING',lease_owner='test-worker',lease_until=now()+interval '5 minutes' WHERE id=?",job);}
    private void run(LessonAuthoringService.Accepted accepted) throws Exception {
        claim(accepted.jobId());handler.execute(jobs.findById(accepted.jobId()).orElseThrow(),mock(JobExecutionContext.class));
        jdbc.update("UPDATE background_job SET status='SUCCEEDED' WHERE id=?",accepted.jobId());
    }
    private static AiExecutionResult result(Map<String,Object> output) {return new AiExecutionResult(UUID.randomUUID(),"MOCK","mock-v1",output,100,30,10);}
}
