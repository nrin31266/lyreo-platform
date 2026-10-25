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
            lesson.sentences.forEach(s->{s.words=List.of();s.issues=List.of("TIMING_MISSING");});
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
        for(int index=0;index<targets.size();index+=6) {
            context.throwIfCancellationRequested();
            var chunk=targets.subList(index,Math.min(index+6,targets.size()));
            var saved=repository.chunk(job.id(),index);
            if(saved.isPresent()) {
                for(var cached:saved.get()) {
                    var target=chunk.stream().filter(s->s.id.equals(cached.id)).findFirst().orElseThrow();
                    copySemantic(cached,target);
                }
                continue;
            }
            var result=invoke(job,AiCapability.GENERAL_LLM,semantic.prompt(lesson.sentences,chunk),semantic.input(lesson.sentences,chunk),
                Map.of("response_schema","lesson_authoring_v1","json_schema",AuthoringSemantic.schema(),"temperature",0.2,"max_output_tokens",3500,
                    "thinking_level","minimal","provider_options",Map.of("reasoning_effort","low")));
            context.throwIfCancellationRequested();semantic.apply(result.output(),chunk);
            committer.stage(job,index,chunk);
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
        repository.usage(job.id(),new AuthoringLesson.Invocation(result.provider(),result.model(),result.inputTokens(),result.outputTokens(),result.latencyMs()));
        return result;
    }
    private String audioUrl(AuthoringLesson lesson) {
        LessonAuthoringService.require(lesson.audioObjectKey!=null,"Prepared audio is missing");
        return storage.createDownloadUrl(lesson.audioObjectKey,Duration.ofHours(1)).toString();
    }
}
