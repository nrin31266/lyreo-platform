package com.lyreo.speechassessment.application;

import tools.jackson.databind.ObjectMapper;
import com.lyreo.ai.application.AiInvocationService;
import com.lyreo.ai.domain.AiCapability;
import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.platform.storage.ObjectStoragePort;
import com.lyreo.speechassessment.domain.SpeechAttempt;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Two-tier speech assessment use case.
 * Tier 1 = deterministic transcript/alignment scoring; Tier 2 = optional multimodal judge.
 */
public final class SpeechAssessmentService {
    private final AiInvocationService ai;
    private final ObjectStoragePort storage;
    private final SpeechAssessmentRepository repository;
    private final SpeechScoringPolicy scoring;
    private final ObjectMapper mapper;

    public SpeechAssessmentService(
        AiInvocationService ai,
        ObjectStoragePort storage,
        SpeechAssessmentRepository repository,
        SpeechScoringPolicy scoring,
        ObjectMapper mapper
    ) {
        this.ai = ai;
        this.storage = storage;
        this.repository = repository;
        this.scoring = scoring;
        this.mapper = mapper;
    }

    public SpeechAttempt assess(
        UUID learnerId,
        UUID lessonId,
        UUID sentenceId,
        String referenceText,
        String recordingObjectKey,
        boolean deepJudge
    ) {
        if (referenceText == null || referenceText.isBlank()) {
            throw new RequestValidationException("referenceText is required");
        }
        if (recordingObjectKey == null || recordingObjectKey.isBlank()) {
            throw new RequestValidationException("recordingObjectKey is required");
        }

        URI audio = storage.createDownloadUrl(recordingObjectKey, Duration.ofMinutes(15));
        var asr = ai.execute(
            AiCapability.STT,
            "",
            Map.of("audio_url", audio.toString(), "expected_text", referenceText),
            Map.of("language", "English", "timestamps", true)
        );
        String transcript = String.valueOf(asr.output().getOrDefault("text", ""));

        var alignment = ai.execute(
            AiCapability.ALIGNMENT,
            "",
            Map.of("audio_url", audio.toString(), "text", referenceText),
            Map.of("language", "English")
        );
        SpeechScoringPolicy.Scores scores = scoring.score(referenceText, transcript, alignment.output());

        Integer deepJudgeScore = null;
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("asr", asr.output());
        raw.put("alignment", alignment.output());

        if (deepJudge) {
            var judge = ai.execute(
                AiCapability.PRONUNCIATION_JUDGE,
                "Evaluate English pronunciation, timing, fluency and prosody. Return concise structured feedback.",
                Map.of(
                    "audio_url", audio.toString(),
                    "reference_text", referenceText,
                    "transcript", transcript
                ),
                Map.of(
                    "language", "English",
                    "temperature", 0.2,
                    "json_schema", pronunciationJudgeSchema()
                )
            );
            raw.put("deepJudge", judge.output());
            Object structured = judge.output().get("structured");
            if (structured instanceof Map<?, ?> result) {
                Object value = result.get("overall");
                if (value instanceof Number number) deepJudgeScore = number.intValue();
            }
        }

        UUID attemptId = UUID.randomUUID();
        String rawKey = "speech-attempts/" + learnerId + "/" + attemptId + "/assessment.json";
        storeRaw(rawKey, raw);

        SpeechAttempt attempt = new SpeechAttempt(
            attemptId,
            learnerId,
            lessonId,
            sentenceId,
            recordingObjectKey,
            transcript,
            scores.wordAccuracy(),
            scores.timingScore(),
            scores.fluencyScore(),
            rawKey,
            Instant.now()
        );
        repository.save(attempt, deepJudgeScore);
        return attempt;
    }

    private static Map<String, Object> pronunciationJudgeSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "overall", Map.of("type", "integer", "minimum", 0, "maximum", 100),
                "fluency", Map.of("type", "integer", "minimum", 0, "maximum", 100),
                "pronunciation", Map.of("type", "integer", "minimum", 0, "maximum", 100),
                "prosody", Map.of("type", "integer", "minimum", 0, "maximum", 100),
                "feedback", Map.of("type", "string")
            ),
            "required", java.util.List.of(
                "overall", "fluency", "pronunciation", "prosody", "feedback"
            )
        );
    }

    private void storeRaw(String objectKey, Map<String, Object> raw) {
        try {
            storage.put(
                objectKey,
                "application/json",
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(raw)
            );
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to store speech assessment artifact", failure);
        }
    }
}
