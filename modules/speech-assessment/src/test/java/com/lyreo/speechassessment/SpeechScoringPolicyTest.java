package com.lyreo.speechassessment;

import static org.assertj.core.api.Assertions.assertThat;

import com.lyreo.speechassessment.application.SpeechScoringPolicy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpeechScoringPolicyTest {

    private final SpeechScoringPolicy policy = new SpeechScoringPolicy();

    @Test
    void exactTranscriptWithReasonableTimingScoresHigh() {
        Map<String, Object> alignment = Map.of(
            "words", List.of(
                Map.of("word", "would", "start_ms", 0, "end_ms", 400),
                Map.of("word", "coffee", "start_ms", 1000, "end_ms", 2500)
            )
        );

        var scores = policy.score(
            "Would you like coffee?",
            "would you like coffee",
            alignment
        );

        assertThat(scores.wordAccuracy()).isEqualTo(100);
        assertThat(scores.fluencyScore()).isBetween(0, 100);
    }

    @Test
    void transcriptErrorReducesWordAccuracyWithoutGoingNegative() {
        var scores = policy.score(
            "please close the door",
            "please open door",
            Map.of()
        );

        assertThat(scores.wordAccuracy()).isBetween(0, 99);
        assertThat(scores.timingScore()).isEqualTo(70);
        assertThat(scores.fluencyScore()).isBetween(0, 100);
    }
}
