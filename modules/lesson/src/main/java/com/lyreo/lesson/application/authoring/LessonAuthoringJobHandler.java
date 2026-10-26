package com.lyreo.lesson.application.authoring;

import com.lyreo.ai.application.execution.AiExecutionException;
import com.lyreo.ai.application.execution.AiExecutionResult;
import com.lyreo.ai.application.execution.AiInvocationService;
import com.lyreo.ai.domain.AiCapability;
import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.lesson.application.build.SourceMaterializationException;
import com.lyreo.lesson.application.port.AuthoringRepository;
import com.lyreo.lesson.application.port.AuthoringSourcePort;
import com.lyreo.lesson.domain.authoring.AuthoringLesson;
import com.lyreo.lesson.domain.authoring.AuthoringSentence;
import com.lyreo.lesson.domain.authoring.AuthoringText;
import com.lyreo.platform.jobs.application.DeferredJobException;
import com.lyreo.platform.jobs.application.JobExecutionContext;
import com.lyreo.platform.jobs.application.JobHandler;
import com.lyreo.platform.jobs.application.PermanentJobException;
import com.lyreo.platform.jobs.domain.BackgroundJob;
import com.lyreo.platform.storage.ObjectStoragePort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class LessonAuthoringJobHandler implements JobHandler {
    private final AuthoringRepository repository;
    private final AuthoringSourcePort source;
    private final ObjectStoragePort storage;
    private final AiInvocationService ai;
    private final AuthoringSemantic semantic;
    private final AuthoringJobCommitter committer;
    public LessonAuthoringJobHandler(AuthoringRepository repository,AuthoringSourcePort source,ObjectStoragePort storage,
        AiInvocationService ai,AuthoringSemantic semantic,AuthoringJobCommitter committer) {
        this.repository=repository;this.source=source;this.storage=storage;this.ai=ai;this.semantic=semantic;this.committer=committer;
    }
    @Override public String jobType() {return LessonAuthoringService.JOB_TYPE;}
    @Override public void execute(BackgroundJob job,JobExecutionContext context) throws Exception {
        var input=repository.jobInput(job.id());
        if(input.completed()) return;
        var lesson=repository.find(input.lessonId(),false).orElseThrow();
        if(!Objects.equals(lesson.activeJobId,job.id()) || lesson.transcriptRevision!=input.transcriptRevision() || lesson.publishedAt!=null)
            throw new PermanentJobException("AUTHORING_STALE","Draft changed before authoring job started");
        try {
            context.throwIfCancellationRequested();
            switch(input.operation()) {
                case "PREPARE" -> prepare(job,context,lesson);
                case "ALIGN" -> align(job,context,lesson);
                case "SEMANTIC" -> generate(job,context,lesson);
                default -> throw new PermanentJobException("AUTHORING_OPERATION_INVALID","Unknown authoring operation");
            }
            context.throwIfCancellationRequested();context.heartbeat();
            committer.commit(job,input,lesson);
            context.progress("CHECKPOINT_SAVED",100);
        } catch(AiExecutionException failure) {
            if(Set.of("AI_HTTP_401","AI_HTTP_403","AI_HTTP_404","AI_HTTP_501","AI_ROUTE_NOT_CONFIGURED","AI_FREE_ONLY_ROUTE_BLOCKED").contains(failure.errorCode()))
                throw new PermanentJobException("AI_CONFIGURATION_REQUIRED",
                    "Configure an enabled provider, supported model and credential in AI Settings, then retry this step. Local runtime cannot execute DEV_MOCK routes.",failure);
            if(failure.retryAfterSeconds()>0)
                throw new DeferredJobException("Free provider quota reached; waiting to resume",Instant.now().plusSeconds(failure.retryAfterSeconds()));
            if(!failure.fallbackAllowed()) throw new PermanentJobException(failure.errorCode(),failure.getMessage(),failure);
            throw failure;
        } catch(SourceMaterializationException failure) {
            if(!failure.retryable()) throw new PermanentJobException(failure.errorCode(),failure.getMessage(),failure);
            throw failure;
        } catch(RequestValidationException failure) {
            throw new PermanentJobException("AUTHORING_VALIDATION_FAILED",failure.getMessage(),failure);
        }
    }
    private void prepare(BackgroundJob job,JobExecutionContext context,AuthoringLesson lesson) {
        context.progress("SOURCE_PREPARE",0);
        lesson.audioObjectKey=source.prepare(lesson.sourceReference,lesson.videoId,lesson.startMs,lesson.endMs);
        context.throwIfCancellationRequested();context.progress("TRANSCRIBING",0);
        var result=invoke(job,AiCapability.STT,"",Map.of("audio_url",audioUrl(lesson)),Map.of("language","English","timestamps",true));
        context.throwIfCancellationRequested();
        Object text=result.output().get("text");
        LessonAuthoringService.require(text instanceof String && !((String)text).isBlank(),"ASR response has no transcript");
        lesson.timingEvidence=java.util.Collections.unmodifiableMap(new java.util.HashMap<>(result.output()));
        lesson.sentences=AuthoringText.split((String)text);
        lesson.timingStatus="MISSING";
        try {
            var words=AuthoringText.observed(result.output());
            try {
                AuthoringText.mapTiming(lesson.sentences,AuthoringText.coalesce(words),lesson.endMs-lesson.startMs);
                LessonAuthoringService.validateTiming(lesson);lesson.timingStatus="DRAFT_VALID";
            } catch(RequestValidationException invalid) {
                // Raw intervals are useful source anchors even when they cannot drive karaoke.
                AuthoringText.mapTiming(lesson.sentences,words,lesson.endMs-lesson.startMs);
                lesson.timingStatus="WHISPER_DRAFT";
            }
        } catch(RequestValidationException invalid) {
            String issue=result.output().get("timestamps") instanceof List<?> timestamps && !timestamps.isEmpty()
                ? (invalid.getMessage().contains("coverage") ? "TIMING_COVERAGE_MISMATCH" : "TIMING_INVALID") : "TIMING_MISSING";
            lesson.sentences.forEach(s->{s.words=List.of();s.issues=List.of(issue);});
        }
        AuthoringText.seedCoarse(lesson.sentences,result.output());
        boolean usable="DRAFT_VALID".equals(lesson.timingStatus);
        lesson.sentences.forEach(s->AuthoringText.eligibility(s,usable));
    }
    private void align(BackgroundJob job,JobExecutionContext context,AuthoringLesson lesson) {
        context.progress("ALIGNING",0);
        var result=invoke(job,AiCapability.ALIGNMENT,"",Map.of("audio_url",audioUrl(lesson),
            "text",String.join(" ",lesson.sentences.stream().map(s->s.text).toList()),
            "segments",LessonAuthoringService.alignmentSegments(lesson)),Map.of("language","English"));
        context.throwIfCancellationRequested();
        AuthoringText.mapTiming(lesson.sentences,AuthoringText.coalesce(AuthoringText.observed(result.output())),lesson.endMs-lesson.startMs);
        LessonAuthoringService.validateTiming(lesson);
        lesson.sentences.forEach(s->AuthoringText.eligibility(s,true));
    }
    private void generate(BackgroundJob job,JobExecutionContext context,AuthoringLesson lesson) {
        LessonAuthoringService.readyForSemantic(lesson);context.progress("GENERATING_CONTENT",0);
        List<AuthoringSentence> targets=lesson.sentences.stream().filter(s->"LEARN".equals(s.usage)).toList();
        // Full lesson context is repeated; only the requested output is bounded to prevent truncation.
        for(int index=0;index<targets.size();) {
            context.throwIfCancellationRequested();
            var saved=repository.chunk(job.id(),index);
            int size=saved.map(List::size).orElse(3);
            LessonAuthoringService.require(saved.isEmpty() || (size>0 && index+size<=targets.size()),"Invalid semantic checkpoint size");
            var chunk=targets.subList(index,Math.min(index+size,targets.size()));
            if(saved.isPresent()) {
                for(var cached:saved.get()) {
                    var target=chunk.stream().filter(s->s.id.equals(cached.id)).findFirst().orElseThrow();
                    copySemantic(cached,target);
                }
                index+=chunk.size();continue;
            }
            String prompt=semantic.prompt(lesson.sentences,chunk);
            var input=semantic.input(lesson.sentences,chunk);
            var options=AuthoringSemantic.options();
            for(int attempt=0;attempt<2;attempt++) {
                context.throwIfCancellationRequested();context.heartbeat();
                var result=invoke(job,AiCapability.GENERAL_LLM,prompt,input,options);
                context.throwIfCancellationRequested();
                try {semantic.apply(result.output(),chunk);break;}
                catch(RequestValidationException invalid) {
                    if(attempt==1) throw invalid;
                    // Core validation messages contain no provider output or source text.
                    prompt=semantic.correctionPrompt(invalid.getMessage());
                }
            }
            committer.stage(job,index,chunk);index+=chunk.size();
        }
    }
    private static void copySemantic(AuthoringSentence from,AuthoringSentence to) {
        to.translation=from.translation;to.vocabulary=from.vocabulary;to.grammar=from.grammar;
        to.dictationPriority=from.dictationPriority;to.shadowingPriority=from.shadowingPriority;
        to.dictationSelected=from.dictationSelected;to.shadowingSelected=from.shadowingSelected;
        to.hints=from.hints;to.notes=from.notes;to.thoughtGroups=from.thoughtGroups;
    }
    private AiExecutionResult invoke(BackgroundJob job,AiCapability capability,String prompt,Map<String,Object> input,Map<String,Object> options) {
        var result=ai.execute(capability,prompt,input,options);
        repository.usage(job.id(),new AuthoringLesson.Invocation(result.provider(),result.model(),result.inputTokens(),result.outputTokens(),result.latencyMs(),
            result.output().get("audio_seconds") instanceof Number seconds ? seconds.doubleValue() : null));
        return result;
    }
    private String audioUrl(AuthoringLesson lesson) {
        LessonAuthoringService.require(lesson.audioObjectKey!=null,"Prepared audio is missing");
        return storage.createDownloadUrl(lesson.audioObjectKey,Duration.ofHours(1)).toString();
    }
}
