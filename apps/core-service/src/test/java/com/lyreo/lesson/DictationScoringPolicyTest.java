package com.lyreo.lesson;

import static org.assertj.core.api.Assertions.assertThat;

import com.lyreo.lesson.application.DictationScoringPolicy;
import org.junit.jupiter.api.Test;

class DictationScoringPolicyTest {
    private final DictationScoringPolicy scoring = new DictationScoringPolicy();

    @Test
    void ignoresCaseAndPunctuationInRelaxedMode() {
        var result = scoring.score(
            "Would you like a cup of coffee?",
            "would you like a cup of coffee"
        );

        assertThat(result.percent()).isEqualTo(100);
    }

    @Test
    void penalizesMissingWordsButNeverTrustsAClientScore() {
        var result = scoring.score(
            "The lecture will take place at six o'clock.",
            "lecture take place six"
        );

        assertThat(result.percent()).isBetween(1, 99);
        assertThat(result.expectedTokenCount()).isGreaterThan(result.answerTokenCount());
    }
}
