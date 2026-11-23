package com.lyreo.speechassessment;

import static org.assertj.core.api.Assertions.assertThat;

import com.lyreo.speechassessment.application.RecordingLimits;
import org.junit.jupiter.api.Test;

class RecordingLimitsTest {
    @Test
    void allowsSlowSpeechBetweenTheMinimumAndCap() {
        var limits = new RecordingLimits(2, 6, 12, 60, 8 * 1024 * 1024);
        assertThat(limits.secondsFor(1_000)).isEqualTo(12);
        assertThat(limits.secondsFor(10_250)).isEqualTo(27);
        assertThat(limits.secondsFor(40_000)).isEqualTo(60);
        assertThat(new RecordingLimits(2, 6, 12, 180, 8 * 1024 * 1024).secondsFor(80_000)).isEqualTo(166);
    }
}
