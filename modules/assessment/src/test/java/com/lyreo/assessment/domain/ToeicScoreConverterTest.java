package com.lyreo.assessment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToeicScoreConverterTest {

    @Test
    void convertsRawCountsToStandardEtsStepsAndClampsBounds() {
        assertThat(ToeicScoreConverter.listeningScaled(-5)).isEqualTo(5);
        assertThat(ToeicScoreConverter.listeningScaled(0)).isEqualTo(5);
        assertThat(ToeicScoreConverter.listeningScaled(100)).isEqualTo(495);
        assertThat(ToeicScoreConverter.listeningScaled(120)).isEqualTo(495);

        assertThat(ToeicScoreConverter.readingScaled(-1)).isEqualTo(5);
        assertThat(ToeicScoreConverter.readingScaled(0)).isEqualTo(5);
        assertThat(ToeicScoreConverter.readingScaled(100)).isEqualTo(495);
        assertThat(ToeicScoreConverter.readingScaled(105)).isEqualTo(495);

        int prevListening = 0;
        int prevReading = 0;
        for (int raw = 0; raw <= 100; raw++) {
            int l = ToeicScoreConverter.listeningScaled(raw);
            int r = ToeicScoreConverter.readingScaled(raw);
            assertThat(l).isBetween(5, 495);
            assertThat(r).isBetween(5, 495);
            assertThat(l % 5).isZero();
            assertThat(r % 5).isZero();
            assertThat(l).isGreaterThanOrEqualTo(prevListening);
            assertThat(r).isGreaterThanOrEqualTo(prevReading);
            prevListening = l;
            prevReading = r;
        }
    }
}
