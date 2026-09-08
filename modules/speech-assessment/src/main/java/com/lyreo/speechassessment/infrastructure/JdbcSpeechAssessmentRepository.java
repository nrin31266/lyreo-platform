package com.lyreo.speechassessment.infrastructure;

import com.lyreo.speechassessment.application.SpeechAssessmentRepository;
import com.lyreo.speechassessment.domain.SpeechAttempt;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcSpeechAssessmentRepository implements SpeechAssessmentRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSpeechAssessmentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SpeechAttempt save(SpeechAttempt attempt, Integer deepJudgeScore) {
        jdbc.update(
            """
            INSERT INTO speech_attempt(
                id, learner_id, lesson_id, sentence_id,
                recording_object_key, asr_text,
                word_accuracy, timing_score, fluency_score, deep_judge_score,
                raw_result_object_key, created_at
            ) VALUES (
                :id, :learnerId, :lessonId, :sentenceId,
                :recordingObjectKey, :asrText,
                :wordAccuracy, :timingScore, :fluencyScore, :deepJudgeScore,
                :rawResultObjectKey, :createdAt
            )
            """,
            new MapSqlParameterSource()
                .addValue("id", attempt.id())
                .addValue("learnerId", attempt.learnerId())
                .addValue("lessonId", attempt.lessonId())
                .addValue("sentenceId", attempt.sentenceId())
                .addValue("recordingObjectKey", attempt.recordingObjectKey())
                .addValue("asrText", attempt.asrText())
                .addValue("wordAccuracy", attempt.wordAccuracy())
                .addValue("timingScore", attempt.timingScore())
                .addValue("fluencyScore", attempt.fluencyScore())
                .addValue("deepJudgeScore", deepJudgeScore)
                .addValue("rawResultObjectKey", attempt.rawResultObjectKey())
                .addValue("createdAt", attempt.createdAt())
        );
        return attempt;
    }
}
