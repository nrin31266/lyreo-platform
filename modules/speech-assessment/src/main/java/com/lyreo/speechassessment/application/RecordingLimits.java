package com.lyreo.speechassessment.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("lyreo.speech-assessment.recording")
public record RecordingLimits(
    @DefaultValue("2.0") double speedFactor,
    @DefaultValue("6") int bufferSeconds,
    @DefaultValue("12") int minSeconds,
    @DefaultValue("60") int hardCapSeconds,
    @DefaultValue("8388608") int maxUploadBytes
) {
    public RecordingLimits {
        if (!Double.isFinite(speedFactor) || speedFactor <= 0 || bufferSeconds < 0
            || minSeconds <= 0 || hardCapSeconds < minSeconds || hardCapSeconds > 180
            || maxUploadBytes <= 0 || maxUploadBytes > 30 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid speech assessment recording limits");
        }
    }

    public int secondsFor(long referenceDurationMs) {
        double scaled = referenceDurationMs * speedFactor / 1_000 + bufferSeconds;
        return (int) Math.ceil(Math.clamp(scaled, minSeconds, hardCapSeconds));
    }
}
