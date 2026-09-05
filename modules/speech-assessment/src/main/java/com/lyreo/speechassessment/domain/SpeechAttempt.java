package com.lyreo.speechassessment.domain;

import java.time.Instant;
import java.util.UUID;

public record SpeechAttempt(
    UUID id,
    UUID learnerId,
    UUID lessonId,
    UUID sentenceId,
    String recordingObjectKey,
    String asrText,
    int wordAccuracy,
    int timingScore,
    int fluencyScore,
    String rawResultObjectKey,
    Instant createdAt
) {
    public int overallScore() {
        return Math.round(wordAccuracy * 0.45f + timingScore * 0.30f + fluencyScore * 0.25f);
    }
}
