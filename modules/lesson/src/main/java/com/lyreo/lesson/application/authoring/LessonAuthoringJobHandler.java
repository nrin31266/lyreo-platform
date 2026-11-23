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
    private com.lyreo.lesson.application.port.AuthoringMediaPort media;
    public void setMedia(com.lyreo.lesson.application.port.AuthoringMediaPort media) {this.media=media;}
    private final AuthoringRepository repository;
    private final AuthoringSourcePort source;
    private final ObjectStoragePort storage;
    private final AiInvocationService ai;
    private final AuthoringJobCommitter committer;
    public LessonAuthoringJobHandler(AuthoringRepository repository,AuthoringSourcePort source,ObjectStoragePort storage,
        AiInvocationService ai,AuthoringJobCommitter committer) {
        this.repository=repository;this.source=source;this.storage=storage;this.ai=ai;this.committer=committer;
    }
    @Override public String jobType() {return LessonAuthoringService.JOB_TYPE;}
    @Override public void execute(BackgroundJob job,JobExecutionContext context) throws Exception {
        var input=repository.jobInput(job.id());
        if(input.completed()) return;
        var lesson=repository.find(input.lessonId(),false).orElseThrow();
        if(!Objects.equals(lesson.activeJobId,job.id()) || lesson.transcriptRevision!=input.transcriptRevision() || lesson.publishedAt!=null)
            throw new PermanentJobException("AUTHORING_STALE","Draft changed before authoring job started");
        if(Set.of("SEMANTIC","IPA").contains(input.operation())) throw new PermanentJobException("AUTHORING_OPERATION_REMOVED","This authoring operation is no longer supported");
        boolean committed=false;
        String newTtsKey=null;
        try (var observation=ai.observe(attempt->{
            repository.usage(job.id(),new AuthoringLesson.Invocation(attempt.provider(),attempt.model(),attempt.inputTokens(),attempt.outputTokens(),attempt.latencyMs(),attempt.output().get("audio_seconds") instanceof Number seconds?seconds.doubleValue():null,
                attempt.invocationId(),attempt.capability(),attempt.error().isEmpty()?"SUCCEEDED":"FAILED",attempt.fallback()));
            var payload=new java.util.LinkedHashMap<String,Object>();payload.put("provider",attempt.provider());payload.put("model",attempt.model());payload.put("capability",attempt.capability());payload.put("fallback",attempt.fallback());payload.put("prompt",attempt.prompt());payload.put("output",attempt.output());payload.put("error",attempt.error());
            payload.putAll(attempt.diagnostics());
            repository.diagnostic(job.id(),attempt.invocationId(),payload);
        })) {
            context.throwIfCancellationRequested();
            switch(input.operation()) {
                case "PREPARE" -> prepare(job,context,lesson);
                case "TTS" -> newTtsKey=synthesize(context,lesson);
                case "ALIGN" -> align(job,context,lesson);
                case "CONTENT" -> new AuthoringContentGenerator(ai,repository,committer).generate(job,input,lesson,context);
                case "SEMANTIC", "IPA" -> throw new PermanentJobException("AUTHORING_OPERATION_REMOVED","This authoring operation is no longer supported. Continue with transcript and timing review.");
                default -> throw new PermanentJobException("AUTHORING_OPERATION_INVALID","Unknown authoring operation");
            }
            context.throwIfCancellationRequested();context.heartbeat();
            committer.commit(job,input,lesson);
            committed=true;
            context.progress("CHECKPOINT_SAVED",100);
        } catch(AiExecutionException failure) {
            repository.diagnostic(job.id(),null,java.util.Map.of("error",String.valueOf(failure.getMessage()),"code",failure.errorCode()));
            if(Set.of("AI_HTTP_401","AI_HTTP_403","AI_HTTP_404","AI_HTTP_501","AI_ROUTE_NOT_CONFIGURED","AI_FREE_ONLY_ROUTE_BLOCKED").contains(failure.errorCode()))
                throw new PermanentJobException("AI_CONFIGURATION_REQUIRED",
                    "Configure an enabled provider, supported model and credential in AI Settings, then retry this step. Local runtime cannot execute DEV_MOCK routes.",failure);
            if(failure.retryAfterSeconds()>0)
                throw new DeferredJobException("Free provider quota reached; waiting to resume",Instant.now().plusSeconds(failure.retryAfterSeconds()));
            if(!failure.fallbackAllowed()) throw new PermanentJobException(failure.errorCode(),failure.getMessage(),failure);
            throw failure;
        } catch(SourceMaterializationException failure) {
            var diagnostic=new java.util.LinkedHashMap<>(failure.diagnostics());
            diagnostic.put("code",failure.errorCode());
            diagnostic.put("operation",input.operation());
            diagnostic.put("attempt",job.attemptCount());
            diagnostic.put("retryable",failure.retryable());
            try {repository.diagnostic(job.id(),null,diagnostic);}
            catch(RuntimeException loggingFailure) {failure.addSuppressed(loggingFailure);}
            if(!failure.retryable()) throw new PermanentJobException(failure.errorCode(),failure.getMessage(),failure);
            throw failure;
        } catch(com.lyreo.lesson.domain.authoring.TimingValidationException failure) {
            throw new PermanentJobException(failure.code(),failure.getMessage(),failure);
        } catch(RequestValidationException failure) {
            throw new PermanentJobException("AUTHORING_VALIDATION_FAILED",failure.getMessage(),failure);
        } catch(RuntimeException failure) {
            var causes=new ArrayList<String>();Throwable cause=failure;
            for(int depth=0;cause!=null && depth<6;depth++,cause=cause.getCause()) causes.add(cause.getClass().getSimpleName()+": "+cause.getMessage());
            repository.diagnostic(job.id(),null,Map.of("causes",causes,"operation",input.operation()));throw failure;
        } finally {
            if(!committed && newTtsKey!=null) {
                try {storage.delete(newTtsKey);} catch(RuntimeException ignored) { /* Keep the original job failure. */ }
            }
        }
    }
    private String synthesize(JobExecutionContext context,AuthoringLesson lesson) {
        LessonAuthoringService.require("TEXT".equals(lesson.sourceType),"Only text lessons use TTS");
        context.progress("SYNTHESIZING",0);
        var result=ai.executeForProvider(AiCapability.TTS,"LOCAL_KOKORO","",
            Map.of("text",lesson.sentences.stream().map(sentence->sentence.text).collect(java.util.stream.Collectors.joining(" ")),
                "sentences",lesson.sentences.stream().map(sentence->sentence.text).toList()),
            Map.of("voice",lesson.ttsVoice));

        context.throwIfCancellationRequested();
        Object raw=result.output().get("audio_base64");
        LessonAuthoringService.require(raw instanceof String && ((String)raw).length()<=90_000_000,"TTS returned no supported audio");
        byte[] audio;
        try {audio=java.util.Base64.getDecoder().decode((String)raw);}
        catch(IllegalArgumentException invalid) {throw new RequestValidationException("TTS returned invalid audio");}
        Object rawSegments=result.output().get("segments");
        LessonAuthoringService.require(rawSegments instanceof List<?> && ((List<?>)rawSegments).size()==lesson.sentences.size(),"TTS returned no sentence timing evidence");
        var segments=(List<?>)rawSegments;
        for(int i=0;i<segments.size();i++) {
            LessonAuthoringService.require(segments.get(i) instanceof Map<?,?>,"Invalid TTS timing evidence");
            var segment=(Map<?,?>)segments.get(i);
            LessonAuthoringService.require(segment.get("start_ms") instanceof Number && segment.get("end_ms") instanceof Number,"Invalid TTS timing evidence");
            var sentence=lesson.sentences.get(i);sentence.coarseStartMs=((Number)segment.get("start_ms")).longValue();sentence.coarseEndMs=((Number)segment.get("end_ms")).longValue();
            LessonAuthoringService.require(sentence.coarseStartMs>=0 && sentence.coarseEndMs>sentence.coarseStartMs
                && sentence.coarseEndMs-sentence.coarseStartMs<=115000,"Split long text sentences before generating speech");
        }
        // A recovered worker can be storing audio for the same job concurrently.
        var stored=media.storeTts(lesson.id,java.util.UUID.randomUUID(),audio);
        lesson.audioObjectKey=stored.key();lesson.endMs=stored.durationMs();
        try {LessonAuthoringService.alignmentSegments(lesson);}
        catch(RuntimeException failure) {
            lesson.audioObjectKey=null;
            try {storage.delete(stored.key());} catch(RuntimeException cleanup) {failure.addSuppressed(cleanup);}
            throw failure;
        }
        return stored.key();
    }
    private void prepare(BackgroundJob job,JobExecutionContext context,AuthoringLesson lesson) {
        context.progress("SOURCE_PREPARE",0);
        lesson.audioObjectKey="AUDIO".equals(lesson.sourceType) ? media.prepare(lesson.audioSourceId,lesson.startMs,lesson.endMs)
            : source.prepare(lesson.sourceReference,lesson.videoId,lesson.startMs,lesson.endMs);
        context.throwIfCancellationRequested();
        var track=AuthoringSourcePort.CaptionTrack.fromEvidence(lesson.timingEvidence);
        if(track!=null || "UPLOADED_EN".equals(lesson.timingEvidence.get("transcriptSource"))) {
            context.progress("FETCHING_CAPTIONS",0);
            if(track!=null) {
                var lines=source.englishCaptions(lesson.videoId,track,lesson.startMs,lesson.endMs);
                lesson.timingEvidence=Map.of("transcriptSource",track.transcriptSource(),"segments",LessonAuthoringService.captionSegments(lines));
            }
            var rows=new ArrayList<AuthoringSentence>();
            for(var entry:(List<?>)lesson.timingEvidence.get("segments")) {
                var line=(Map<?,?>)entry;
                var sentence=new AuthoringSentence(java.util.UUID.randomUUID(),(String)line.get("text"),"LEARN");
                sentence.coarseStartMs=((Number)line.get("start_ms")).longValue();
                sentence.coarseEndMs=((Number)line.get("end_ms")).longValue();
                rows.add(sentence);
            }
            lesson.sentences=rows;lesson.timingStatus="MISSING";
            LessonAuthoringService.alignmentSegments(lesson);
            return;
        }
        context.progress("TRANSCRIBING",0);
        var input=Map.<String,Object>of("audio_url",audioUrl(lesson));
        var options=Map.<String,Object>of("language","English","timestamps",true,"allow_alignment",false);
        var result=Boolean.TRUE.equals(lesson.timingEvidence.get("useWhisperX"))
            ? ai.executeForProvider(AiCapability.STT,"LOCAL_WHISPERX","",input,options)
            : invoke(job,AiCapability.STT,"",input,options);
        context.throwIfCancellationRequested();
        Object text=result.output().get("text");
        LessonAuthoringService.require(text instanceof String && !((String)text).isBlank(),"ASR response has no transcript");
        var evidence=new java.util.HashMap<>(result.output());
        evidence.put("transcriptSource",result.provider());
        lesson.timingEvidence=java.util.Collections.unmodifiableMap(evidence);
        lesson.sentences=AuthoringText.split((String)text);
        lesson.timingStatus="MISSING";
        try {
            var words=AuthoringText.observed(result.output());
            try {
                AuthoringText.mapTiming(lesson.sentences,AuthoringText.coalesce(lesson.sentences,words),lesson.endMs-lesson.startMs);
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
    }
    private void align(BackgroundJob job,JobExecutionContext context,AuthoringLesson lesson) {
        context.progress("ALIGNING",0);
        var sentences=lesson.sentences;
        var segments=LessonAuthoringService.alignmentSegments(lesson);
        var sourceAnchors=sentences.stream().collect(java.util.stream.Collectors.toMap(sentence->sentence.id,
            sentence->new AuthoringText.ObservedWord(sentence.text,sentence.coarseStartMs,sentence.coarseEndMs)));
        var result=invoke(job,AiCapability.ALIGNMENT,"",Map.of("audio_url",audioUrl(lesson),
            "text",String.join(" ",sentences.stream().map(s->s.text).toList()),
            "segments",segments),Map.of("language","English"));
        context.throwIfCancellationRequested();
        AuthoringText.mapTiming(sentences,AuthoringText.coalesce(sentences,AuthoringText.observed(result.output())),lesson.endMs-lesson.startMs);
        AuthoringText.groupUnresolvedTiming(sentences);
        recoverUnresolved(job,context,lesson,sourceAnchors);
        LessonAuthoringService.validateTiming(lesson);
    }
    private void recoverUnresolved(BackgroundJob job,JobExecutionContext context,AuthoringLesson lesson,
        Map<java.util.UUID,AuthoringText.ObservedWord> sourceAnchors) {
        var sentences=lesson.sentences;
        var unresolved=sentences.stream().filter(sentence->sentence.words.stream()
            .noneMatch(word->word.endMs()>word.startMs())).toList();
        if(unresolved.isEmpty() || unresolved.size()==sentences.size()) return;
        var segments=new java.util.ArrayList<Map<String,Object>>();long previousEnd=-1;
        for(var sentence:unresolved) {
            var anchor=sourceAnchors.get(sentence.id);
            if(anchor==null || anchor.startMs()<previousEnd) return;
            segments.add(Map.of("text",sentence.text,"start_ms",anchor.startMs(),"end_ms",anchor.endMs()));
            previousEnd=anchor.endMs();
        }
        context.throwIfCancellationRequested();
        // One anchored pass for short unresolved fragments; global validation still rejects overlap or missing speech.
        var result=invoke(job,AiCapability.ALIGNMENT,"",Map.of("audio_url",audioUrl(lesson),
            "text",String.join(" ",unresolved.stream().map(sentence->sentence.text).toList()),"segments",segments),Map.of("language","English"));
        context.throwIfCancellationRequested();
        AuthoringText.mapTiming(unresolved,AuthoringText.coalesce(unresolved,AuthoringText.observed(result.output())),lesson.endMs-lesson.startMs);
        AuthoringText.groupUnresolvedTiming(unresolved);
    }
    private AiExecutionResult invoke(BackgroundJob job,AiCapability capability,String prompt,Map<String,Object> input,Map<String,Object> options) {
        return ai.execute(capability,prompt,input,options);
    }
    private String audioUrl(AuthoringLesson lesson) {
        LessonAuthoringService.require(lesson.audioObjectKey!=null,"Prepared audio is missing");
        return storage.createDownloadUrl(lesson.audioObjectKey,Duration.ofHours(1)).toString();
    }
}
