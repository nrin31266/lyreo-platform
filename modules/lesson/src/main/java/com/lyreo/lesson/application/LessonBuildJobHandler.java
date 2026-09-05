package com.lyreo.lesson.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyreo.ai.application.AiExecutionException;
import com.lyreo.ai.application.AiExecutionResult;
import com.lyreo.ai.application.AiInvocationService;
import com.lyreo.ai.domain.AiCapability;
import com.lyreo.lesson.domain.Lesson;
import com.lyreo.lesson.domain.LessonAnnotationType;
import com.lyreo.lesson.domain.LessonBuildPlan;
import com.lyreo.lesson.domain.LessonBuildStep;
import com.lyreo.lesson.domain.LessonSentence;
import com.lyreo.platform.jobs.application.JobExecutionContext;
import com.lyreo.platform.jobs.application.JobHandler;
import com.lyreo.platform.jobs.application.PermanentJobException;
import com.lyreo.platform.jobs.domain.BackgroundJob;
import com.lyreo.platform.storage.ObjectStoragePort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Durable lesson builder. Business orchestration stays in Java; FastAPI only executes
 * the requested AI capability. Each step is idempotent and persisted independently.
 */
public final class LessonBuildJobHandler implements JobHandler {
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+");

    private final LessonRepository lessons;
    private final LessonBuildStateRepository state;
    private final AiInvocationService ai;
    private final ObjectStoragePort storage;
    private final LessonActivityWriter activityWriter;
    private final LessonEnrichmentWriter enrichmentWriter;
    private final LessonPromptFactory prompts;
    private final LessonSourceMaterializer sourceMaterializer;
    private final ObjectMapper mapper;

    public LessonBuildJobHandler(
        LessonRepository lessons,
        LessonBuildStateRepository state,
        AiInvocationService ai,
        ObjectStoragePort storage,
        LessonActivityWriter activityWriter,
        LessonEnrichmentWriter enrichmentWriter,
        LessonPromptFactory prompts,
        LessonSourceMaterializer sourceMaterializer,
        ObjectMapper mapper
    ) {
        this.lessons = lessons;
        this.state = state;
        this.ai = ai;
        this.storage = storage;
        this.activityWriter = activityWriter;
        this.enrichmentWriter = enrichmentWriter;
        this.prompts = prompts;
        this.sourceMaterializer = sourceMaterializer;
        this.mapper = mapper;
    }

    @Override
    public String jobType() {
        return CreateLessonBuildService.JOB_TYPE;
    }

    @Override
    public void execute(BackgroundJob job, JobExecutionContext context) throws Exception {
        UUID lessonId = state.lessonIdForJob(job.id());
        LessonBuildPlan plan = state.planForJob(job.id());
        int total = Math.max(1, plan.steps().size());
        int index = 0;

        for (LessonBuildStep step : plan.steps()) {
            context.throwIfCancellationRequested();
            if (state.stepDone(job.id(), step)) {
                index++;
                continue;
            }

            int percent = Math.max(1, (index * 100) / total);
            context.progress(step.name(), percent);
            state.stepStarted(job.id(), step);

            try {
                StepOutcome outcome = executeStep(job.id(), lessonId, plan, step, context);
                context.throwIfCancellationRequested();
                state.stepDone(job.id(), step, outcome.aiInvocationId(), outcome.artifactKey());
            } catch (SourceMaterializationException sourceFailure) {
                state.stepFailed(job.id(), step, safeMessage(sourceFailure));
                if (!sourceFailure.retryable()) {
                    throw new PermanentJobException(
                        sourceFailure.errorCode(), safeMessage(sourceFailure), sourceFailure
                    );
                }
                throw sourceFailure;
            } catch (AiExecutionException aiFailure) {
                state.stepFailed(job.id(), step, safeMessage(aiFailure));
                if (!aiFailure.fallbackAllowed()) {
                    throw new PermanentJobException(
                        aiFailure.errorCode(), safeMessage(aiFailure), aiFailure
                    );
                }
                throw aiFailure;
            } catch (Exception failure) {
                state.stepFailed(job.id(), step, safeMessage(failure));
                throw failure;
            }
            index++;
        }
        context.progress(LessonBuildStep.FINALIZE.name(), 100);
    }

    private StepOutcome executeStep(
        UUID jobId,
        UUID lessonId,
        LessonBuildPlan plan,
        LessonBuildStep step,
        JobExecutionContext context
    ) throws Exception {
        Lesson lesson = lessons.findById(lessonId).orElseThrow();

        return switch (step) {
            case SOURCE_PREPARE -> {
                prepareSource(lesson);
                sourceMaterializer.materialize(lesson)
                    .ifPresent(objectKey -> lessons.setCanonicalAudio(lesson.id(), objectKey));
                yield StepOutcome.none();
            }
            case STT -> stt(jobId, lesson, context);
            case TTS -> tts(jobId, lesson, plan, context);
            case ALIGNMENT -> alignment(jobId, lessonId, lesson, context);
            case NLP -> nlp(jobId, lessonId, plan, context);
            case TRANSLATION -> annotation(
                jobId,
                lessonId,
                "translation",
                LessonAnnotationType.TRANSLATION,
                prompts.translation(sentenceTexts(lessonId)),
                "sentence_translation_v1",
                context
            );
            case LEXICAL_ANALYSIS -> annotation(
                jobId,
                lessonId,
                "lexical",
                LessonAnnotationType.LEXICAL,
                prompts.lexicalDetection(sentenceTexts(lessonId)),
                "lesson_lexical_annotations_v1",
                context
            );
            case GRAMMAR_ANALYSIS -> annotation(
                jobId,
                lessonId,
                "grammar",
                LessonAnnotationType.GRAMMAR,
                prompts.grammarDetection(sentenceTexts(lessonId)),
                "lesson_grammar_annotations_v1",
                context
            );
            case THOUGHT_GROUP_ANALYSIS -> annotation(
                jobId,
                lessonId,
                "thought-groups",
                LessonAnnotationType.THOUGHT_GROUPS,
                prompts.thoughtGroups(sentenceTexts(lessonId)),
                "sentence_thought_groups_v1",
                context
            );
            case LEARNING_TIPS -> annotation(
                jobId,
                lessonId,
                "learning-tips",
                LessonAnnotationType.LEARNING_TIPS,
                prompts.learningTips(sentenceTexts(lessonId)),
                "lesson_learning_tips_v1",
                context
            );
            case PRONUNCIATION_ENRICHMENT -> sentenceIpa(jobId, lessonId, plan, context);
            case ACTIVITY_BUILD -> {
                activityWriter.replace(lessonId, plan.options().activities());
                yield StepOutcome.none();
            }
            case FINALIZE -> {
                lessons.markReady(lessonId);
                yield StepOutcome.none();
            }
        };
    }

    private void prepareSource(Lesson lesson) {
        if (lesson.sourceText() == null || lesson.sourceText().isBlank()) {
            // AUDIO/YOUTUBE transcript is filled by STT.
            return;
        }
        lessons.replaceSentences(lesson.id(), splitSentences(lesson.sourceText()));
    }

    private StepOutcome stt(UUID jobId, Lesson lesson, JobExecutionContext context) {
        AiExecutionResult result = ai.execute(
            AiCapability.STT,
            "",
            Map.of(
                "audio_url", audioDownloadUrl(lesson),
                "lesson_id", lesson.id().toString()
            ),
            Map.of("language", "English", "timestamps", false)
        );
        context.throwIfCancellationRequested();
        Object text = result.output().get("text");
        if (!(text instanceof String transcript) || transcript.isBlank()) {
            throw new IllegalStateException("STT response does not contain transcript text");
        }
        lessons.replaceSentences(lesson.id(), splitSentences(transcript));
        String artifact = saveRawAiArtifact(jobId, "stt", result);
        return StepOutcome.ai(result, artifact);
    }

    private StepOutcome tts(UUID jobId, Lesson lesson, LessonBuildPlan plan, JobExecutionContext context) {
        String text = currentText(lesson.id(), lesson);
        AiExecutionResult result = ai.execute(
            AiCapability.TTS,
            "",
            Map.of("text", text),
            Map.of(
                "language", "en-US",
                "accent", plan.options().accent()
            )
        );
        context.throwIfCancellationRequested();
        Object audioBase64 = result.output().get("audio_base64");
        if (!(audioBase64 instanceof String encoded) || encoded.isBlank()) {
            throw new IllegalStateException("TTS response does not contain audio_base64");
        }
        byte[] bytes = Base64.getDecoder().decode(encoded);
        String mime = String.valueOf(result.output().getOrDefault("mime_type", "audio/mpeg"));
        String extension = mime.contains("wav") ? "wav" : "mp3";
        String key = "lessons/" + lesson.id() + "/audio/canonical." + extension;
        storage.put(key, mime, bytes);
        lessons.setCanonicalAudio(lesson.id(), key);
        String artifact = saveRawAiArtifact(jobId, "tts", result);
        return StepOutcome.ai(result, artifact);
    }

    private StepOutcome alignment(UUID jobId, UUID lessonId, Lesson lesson, JobExecutionContext context) {
        AiExecutionResult result = ai.execute(
            AiCapability.ALIGNMENT,
            "",
            Map.of(
                "lesson_id", lessonId.toString(),
                "audio_url", audioDownloadUrl(lesson),
                "text", currentText(lessonId, lesson)
            ),
            Map.of("language", "English")
        );
        context.throwIfCancellationRequested();
        List<LessonSentence> sentences = lessons.sentences(lessonId);
        enrichmentWriter.replaceAlignment(lessonId, sentences, result.output());
        String artifact = saveRawAiArtifact(jobId, "alignment", result);
        return StepOutcome.ai(result, artifact);
    }

    private StepOutcome nlp(UUID jobId, UUID lessonId, LessonBuildPlan plan, JobExecutionContext context) {
        AiExecutionResult result = ai.execute(
            AiCapability.NLP,
            "",
            Map.of("sentences", sentenceTexts(lessonId)),
            Map.of("language", "en")
        );
        context.throwIfCancellationRequested();
        boolean entityHints = plan.options().annotations().contains(LessonAnnotationType.ENTITY_HINTS);
        boolean dictationHints = plan.options().annotations().contains(LessonAnnotationType.DICTATION_HINTS);
        enrichmentWriter.replaceNlpHints(lessonId, lessons.sentences(lessonId), result.output(), entityHints, dictationHints);
        String artifact = saveRawAiArtifact(jobId, "nlp", result);
        return StepOutcome.ai(result, artifact);
    }

    private StepOutcome annotation(
        UUID jobId,
        UUID lessonId,
        String artifactName,
        LessonAnnotationType annotationType,
        String prompt,
        String schema,
        JobExecutionContext context
    ) {
        List<String> texts = sentenceTexts(lessonId);
        AiExecutionResult result = ai.execute(
            AiCapability.GENERAL_LLM,
            prompt,
            Map.of("sentences", texts),
            Map.of("response_schema", schema, "temperature", 0.2)
        );
        context.throwIfCancellationRequested();
        enrichmentWriter.replaceStructuredAnnotations(
            lessonId,
            annotationType.name(),
            lessons.sentences(lessonId),
            structured(result)
        );
        String artifact = saveRawAiArtifact(jobId, artifactName, result);
        return StepOutcome.ai(result, artifact);
    }

    private StepOutcome sentenceIpa(UUID jobId, UUID lessonId, LessonBuildPlan plan, JobExecutionContext context) {
        List<String> texts = sentenceTexts(lessonId);
        AiExecutionResult result = ai.execute(
            AiCapability.GENERAL_LLM,
            prompts.sentenceIpa(texts, plan.options().accent()),
            Map.of("sentences", texts, "accent", plan.options().accent()),
            Map.of("response_schema", "sentence_ipa_v1", "temperature", 0.1)
        );
        context.throwIfCancellationRequested();
        Object structured = structured(result);
        enrichmentWriter.replaceStructuredAnnotations(
            lessonId,
            LessonAnnotationType.SENTENCE_IPA.name(),
            lessons.sentences(lessonId),
            structured
        );
        enrichmentWriter.replaceSentenceIpa(
            lessonId,
            lessons.sentences(lessonId),
            structured,
            result.provider(),
            result.model(),
            plan.options().accent()
        );
        String artifact = saveRawAiArtifact(jobId, "sentence-ipa", result);
        return StepOutcome.ai(result, artifact);
    }

    private Object structured(AiExecutionResult result) {
        Object value = result.output().get("structured");
        if (value != null) return value;
        Object content = result.output().get("content");
        if (content instanceof String json && !json.isBlank()) {
            try {
                JsonNode node = mapper.readTree(stripMarkdownFence(json));
                if (node != null) return node;
            } catch (Exception ignored) {
                // Keep the business step durable even when a provider does not honor JSON mode.
            }
        }
        return Map.of();
    }

    private String saveRawAiArtifact(UUID jobId, String name, AiExecutionResult result) {
        try {
            byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of(
                "invocation_id", result.invocationId().toString(),
                "provider", result.provider(),
                "model", result.model(),
                "latency_ms", result.latencyMs(),
                "input_tokens", result.inputTokens() == null ? 0 : result.inputTokens(),
                "output_tokens", result.outputTokens() == null ? 0 : result.outputTokens(),
                "output", result.output()
            ));
            String key = "jobs/" + jobId + "/" + name + "/raw.json";
            storage.put(key, "application/json", json);
            return key;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to store raw AI artifact", e);
        }
    }

    private String currentText(UUID lessonId, Lesson lesson) {
        List<String> texts = sentenceTexts(lessonId);
        if (!texts.isEmpty()) return String.join(" ", texts);
        if (lesson.sourceText() != null && !lesson.sourceText().isBlank()) return lesson.sourceText();
        throw new IllegalStateException("Lesson has no transcript");
    }

    private List<String> sentenceTexts(UUID lessonId) {
        return lessons.sentences(lessonId).stream().map(LessonSentence::text).toList();
    }

    private String sourceDownloadUrl(Lesson lesson) {
        String source = lesson.sourceReference();
        if (source == null || source.isBlank()) {
            throw new IllegalStateException("Lesson has no source reference for speech recognition");
        }
        if (isRemoteUrl(source)) return source;
        return storage.createDownloadUrl(source, Duration.ofMinutes(20)).toString();
    }

    private String audioDownloadUrl(Lesson lesson) {
        Lesson fresh = lessons.findById(lesson.id()).orElseThrow();
        if (fresh.canonicalAudioObjectKey() != null && !fresh.canonicalAudioObjectKey().isBlank()) {
            return storage.createDownloadUrl(fresh.canonicalAudioObjectKey(), Duration.ofMinutes(20)).toString();
        }
        return sourceDownloadUrl(fresh);
    }

    private static boolean isRemoteUrl(String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private static List<String> splitSentences(String text) {
        List<String> result = new ArrayList<>();
        for (String candidate : SENTENCE_SPLIT.split(text.strip())) {
            String normalized = candidate.strip();
            if (!normalized.isBlank()) result.add(normalized);
        }
        return result.isEmpty() ? List.of(text.strip()) : List.copyOf(result);
    }

    private static String stripMarkdownFence(String value) {
        String text = value.strip();
        if (!text.startsWith("```")) return text;
        int firstNewline = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        if (firstNewline >= 0 && lastFence > firstNewline) {
            return text.substring(firstNewline + 1, lastFence).strip();
        }
        return text;
    }

    private static String safeMessage(Throwable throwable) {
        String value = throwable.getMessage();
        if (value == null || value.isBlank()) return throwable.getClass().getSimpleName();
        return value.substring(0, Math.min(2000, value.length()));
    }

    private record StepOutcome(UUID aiInvocationId, String artifactKey) {
        static StepOutcome none() {
            return new StepOutcome(null, null);
        }

        static StepOutcome ai(AiExecutionResult result, String artifactKey) {
            return new StepOutcome(result.invocationId(), artifactKey);
        }
    }
}
