package com.lyreo.lesson;

import static org.assertj.core.api.Assertions.assertThat;

import com.lyreo.lesson.application.LessonPromptFactory;
import java.util.List;
import org.junit.jupiter.api.Test;

class LessonPromptFactoryTest {
    private final LessonPromptFactory prompts = new LessonPromptFactory();

    @Test
    void lexicalPromptKeepsProductSemanticsInJava() {
        String prompt = prompts.lexicalDetection(List.of("We called off the meeting."));
        assertThat(prompt)
            .contains("PHRASAL_VERB")
            .contains("context_meaning_vi")
            .contains("We called off the meeting.");
    }

    @Test
    void grammarPromptExplicitlyUsesCuratedPracticeBank() {
        assertThat(prompts.grammarDetection(List.of("If I had known, I would have called.")))
            .contains("Do not generate practice questions")
            .contains("Grammar Bank");
    }
}
