package com.lyreo.lesson.application;

import com.lyreo.lesson.domain.LessonActivityType;
import com.lyreo.lesson.domain.LessonAnnotationType;
import com.lyreo.lesson.domain.LessonBuildOptions;
import com.lyreo.lesson.domain.LessonBuildPlan;
import com.lyreo.lesson.domain.LessonBuildStep;
import com.lyreo.lesson.domain.LessonSourceType;
import java.util.ArrayList;
import java.util.List;

/** Calculates the minimum durable workflow needed for one lesson configuration. */
public final class LessonBuildPlanner {

    public LessonBuildPlan plan(LessonBuildOptions options) {
        List<LessonBuildStep> steps = new ArrayList<>();
        steps.add(LessonBuildStep.SOURCE_PREPARE);

        if (options.sourceType() == LessonSourceType.AUDIO || options.sourceType() == LessonSourceType.YOUTUBE) {
            steps.add(LessonBuildStep.STT);
        }

        boolean audioActivity =
            options.activities().contains(LessonActivityType.DICTATION) ||
            options.activities().contains(LessonActivityType.SHADOWING);

        if (options.sourceType() == LessonSourceType.TEXT && audioActivity) {
            steps.add(LessonBuildStep.TTS);
        }

        // Precise timestamps power sentence repeat, karaoke highlighting, dictation and shadowing.
        if (audioActivity || options.annotations().contains(LessonAnnotationType.THOUGHT_GROUPS)) {
            steps.add(LessonBuildStep.ALIGNMENT);
        }

        boolean needsNlp =
            options.annotations().contains(LessonAnnotationType.LEXICAL) ||
            options.annotations().contains(LessonAnnotationType.GRAMMAR) ||
            options.annotations().contains(LessonAnnotationType.ENTITY_HINTS) ||
            options.annotations().contains(LessonAnnotationType.DICTATION_HINTS) ||
            options.annotations().contains(LessonAnnotationType.THOUGHT_GROUPS) ||
            options.activities().contains(LessonActivityType.VOCABULARY_PRACTICE) ||
            options.activities().contains(LessonActivityType.GRAMMAR_PRACTICE);

        if (needsNlp) steps.add(LessonBuildStep.NLP);
        if (options.annotations().contains(LessonAnnotationType.TRANSLATION)) steps.add(LessonBuildStep.TRANSLATION);

        if (options.annotations().contains(LessonAnnotationType.LEXICAL) ||
            options.activities().contains(LessonActivityType.VOCABULARY_PRACTICE)) {
            steps.add(LessonBuildStep.LEXICAL_ANALYSIS);
        }

        if (options.annotations().contains(LessonAnnotationType.GRAMMAR) ||
            options.activities().contains(LessonActivityType.GRAMMAR_PRACTICE)) {
            steps.add(LessonBuildStep.GRAMMAR_ANALYSIS);
        }

        if (options.annotations().contains(LessonAnnotationType.THOUGHT_GROUPS)) {
            steps.add(LessonBuildStep.THOUGHT_GROUP_ANALYSIS);
        }
        if (options.annotations().contains(LessonAnnotationType.LEARNING_TIPS)) {
            steps.add(LessonBuildStep.LEARNING_TIPS);
        }

        if (options.annotations().contains(LessonAnnotationType.SENTENCE_IPA) &&
            "PREGENERATE".equalsIgnoreCase(options.pronunciationStrategy())) {
            steps.add(LessonBuildStep.PRONUNCIATION_ENRICHMENT);
        }

        steps.add(LessonBuildStep.ACTIVITY_BUILD);
        steps.add(LessonBuildStep.FINALIZE);
        return new LessonBuildPlan(options.sourceType(), List.copyOf(steps), options);
    }
}
