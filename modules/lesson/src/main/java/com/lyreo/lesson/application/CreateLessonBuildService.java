package com.lyreo.lesson.application;

import tools.jackson.databind.ObjectMapper;
import com.lyreo.ai.application.AiRoutingSnapshotService;
import com.lyreo.ai.domain.AiCapability;
import com.lyreo.lesson.domain.Lesson;
import com.lyreo.lesson.domain.LessonBuildOptions;
import com.lyreo.lesson.domain.LessonBuildPlan;
import com.lyreo.lesson.domain.LessonBuildStep;
import com.lyreo.platform.jobs.application.BackgroundJobService;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class CreateLessonBuildService {
    public static final String JOB_TYPE = "LESSON_BUILD";

    private final LessonRepository lessons;
    private final LessonBuildPlanner planner;
    private final LessonBuildStateRepository state;
    private final LessonProcessingPolicyRepository processingPolicy;
    private final BackgroundJobService jobs;
    private final AiRoutingSnapshotService aiRoutingSnapshot;
    private final ObjectMapper mapper;

    public CreateLessonBuildService(
        LessonRepository lessons,
        LessonBuildPlanner planner,
        LessonBuildStateRepository state,
        LessonProcessingPolicyRepository processingPolicy,
        BackgroundJobService jobs,
        AiRoutingSnapshotService aiRoutingSnapshot,
        ObjectMapper mapper
    ) {
        this.lessons = lessons;
        this.planner = planner;
        this.state = state;
        this.processingPolicy = processingPolicy;
        this.jobs = jobs;
        this.aiRoutingSnapshot = aiRoutingSnapshot;
        this.mapper = mapper;
    }

    /**
     * Accepts a lesson build atomically: draft Lesson, generic background job and
     * Lesson-specific build plan either commit together or roll back together.
     */
    @Transactional
    public BuildAccepted create(
        String title,
        String sourceText,
        String sourceReference,
        LessonBuildOptions options
    ) {
        validateRequest(title, sourceText, sourceReference, options);
        processingPolicy.current().validate(options);

        try {
            Lesson lesson = lessons.createDraft(
                title.strip(),
                options.sourceType().name(),
                normalizeBlank(sourceText),
                normalizeBlank(sourceReference)
            );
            LessonBuildPlan plan = planner.plan(options);
            String jobConfigSnapshot = mapper.writeValueAsString(Map.of(
                "lessonId", lesson.id(),
                "options", options,
                "steps", plan.steps()
            ));
            String providerSnapshot = mapper.writeValueAsString(
                aiRoutingSnapshot.snapshot(requiredAiCapabilities(plan))
            );

            UUID jobId = jobs.enqueue(
                JOB_TYPE,
                "lesson",
                lesson.id(),
                50,
                3,
                jobConfigSnapshot
            );
            state.create(jobId, lesson.id(), plan, providerSnapshot);
            lessons.markProcessing(lesson.id());
            return new BuildAccepted(lesson.id(), jobId, plan);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to create lesson build", failure);
        }
    }

    private static Set<AiCapability> requiredAiCapabilities(LessonBuildPlan plan) {
        Set<AiCapability> capabilities = new LinkedHashSet<>();
        for (LessonBuildStep step : plan.steps()) {
            switch (step) {
                case STT -> capabilities.add(AiCapability.STT);
                case TTS -> capabilities.add(AiCapability.TTS);
                case ALIGNMENT -> capabilities.add(AiCapability.ALIGNMENT);
                case NLP -> capabilities.add(AiCapability.NLP);
                case TRANSLATION,
                     LEXICAL_ANALYSIS,
                     GRAMMAR_ANALYSIS,
                     THOUGHT_GROUP_ANALYSIS,
                     LEARNING_TIPS,
                     PRONUNCIATION_ENRICHMENT -> capabilities.add(AiCapability.GENERAL_LLM);
                case SOURCE_PREPARE, ACTIVITY_BUILD, FINALIZE -> {
                    // Pure Java/database steps do not require an AI route.
                }
            }
        }
        return Set.copyOf(capabilities);
    }

    private static void validateRequest(
        String title,
        String sourceText,
        String sourceReference,
        LessonBuildOptions options
    ) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Lesson title is required");
        }
        if (options == null) {
            throw new IllegalArgumentException("Lesson build options are required");
        }
        switch (options.sourceType()) {
            case TEXT -> {
                if (sourceText == null || sourceText.isBlank()) {
                    throw new IllegalArgumentException("TEXT lesson requires sourceText");
                }
            }
            case AUDIO, YOUTUBE -> {
                if (sourceReference == null || sourceReference.isBlank()) {
                    throw new IllegalArgumentException(
                        options.sourceType() + " lesson requires sourceReference"
                    );
                }
            }
        }
    }

    private static String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record BuildAccepted(UUID lessonId, UUID jobId, LessonBuildPlan plan) {}
}
