package com.lyreo.lesson;

import static org.assertj.core.api.Assertions.assertThat;

import com.lyreo.lesson.application.LessonBuildPlanner;
import com.lyreo.lesson.domain.LessonActivityType;
import com.lyreo.lesson.domain.LessonAnnotationType;
import com.lyreo.lesson.domain.LessonBuildOptions;
import com.lyreo.lesson.domain.LessonBuildStep;
import com.lyreo.lesson.domain.LessonSourceType;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LessonBuildPlannerTest {
    private final LessonBuildPlanner planner = new LessonBuildPlanner();

    @Test
    void plainTextWithoutAudioActivitiesDoesNotSpendOnTtsOrAlignment() {
        var plan = planner.plan(new LessonBuildOptions(
            LessonSourceType.TEXT,
            Set.of(),
            Set.of(LessonAnnotationType.TRANSLATION),
            "US",
            "ON_DEMAND"
        ));

        assertThat(plan.steps()).contains(LessonBuildStep.TRANSLATION, LessonBuildStep.FINALIZE);
        assertThat(plan.steps()).doesNotContain(LessonBuildStep.TTS, LessonBuildStep.ALIGNMENT, LessonBuildStep.STT);
    }

    @Test
    void textShadowingRequiresTtsAndAlignment() {
        var plan = planner.plan(new LessonBuildOptions(
            LessonSourceType.TEXT,
            Set.of(LessonActivityType.SHADOWING),
            Set.of(LessonAnnotationType.THOUGHT_GROUPS),
            "US",
            "ON_DEMAND"
        ));

        assertThat(plan.steps()).containsSubsequence(
            LessonBuildStep.SOURCE_PREPARE,
            LessonBuildStep.TTS,
            LessonBuildStep.ALIGNMENT,
            LessonBuildStep.NLP,
            LessonBuildStep.THOUGHT_GROUP_ANALYSIS,
            LessonBuildStep.ACTIVITY_BUILD,
            LessonBuildStep.FINALIZE
        );
    }

    @Test
    void sentenceIpaIsOnlyPregeneratedWhenAdminPolicyRequestsIt() {
        var lazy = planner.plan(new LessonBuildOptions(
            LessonSourceType.TEXT, Set.of(), Set.of(LessonAnnotationType.SENTENCE_IPA), "US", "ON_DEMAND"
        ));
        var pregenerated = planner.plan(new LessonBuildOptions(
            LessonSourceType.TEXT, Set.of(), Set.of(LessonAnnotationType.SENTENCE_IPA), "US", "PREGENERATE"
        ));

        assertThat(lazy.steps()).doesNotContain(LessonBuildStep.PRONUNCIATION_ENRICHMENT);
        assertThat(pregenerated.steps()).contains(LessonBuildStep.PRONUNCIATION_ENRICHMENT);
    }
}
