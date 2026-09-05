package com.lyreo.toeic.infrastructure;

import com.lyreo.toeic.application.ToeicAttemptRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcToeicAttemptRepository implements ToeicAttemptRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcToeicAttemptRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<QuestionKey> answerKey(UUID testId, Set<UUID> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) {
            return jdbc.query("""
                SELECT id, part, correct_answer
                  FROM toeic_question
                 WHERE test_id = :testId
                 ORDER BY question_number NULLS LAST, id
                """, Map.of("testId", testId), (rs, rowNum) -> new QuestionKey(
                    rs.getObject("id", UUID.class),
                    rs.getObject("part", Integer.class),
                    rs.getString("correct_answer")
                ));
        }

        return jdbc.query("""
            SELECT id, part, correct_answer
              FROM toeic_question
             WHERE test_id = :testId
               AND id IN (:questionIds)
             ORDER BY question_number NULLS LAST, id
            """, new MapSqlParameterSource()
            .addValue("testId", testId)
            .addValue("questionIds", questionIds),
            (rs, rowNum) -> new QuestionKey(
                rs.getObject("id", UUID.class),
                rs.getObject("part", Integer.class),
                rs.getString("correct_answer")
            ));
    }

    @Override
    public UUID saveCompletedAttempt(
        UUID learnerId,
        UUID testId,
        String mode,
        ScoreSummary score,
        Map<UUID, String> submittedAnswers,
        List<QuestionKey> answerKey
    ) {
        UUID attemptId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO toeic_attempt(
              id,
              learner_id,
              test_id,
              mode,
              status,
              raw_listening_correct,
              raw_reading_correct,
              listening_score,
              reading_score,
              started_at,
              submitted_at
            ) VALUES (
              :id,
              :learnerId,
              :testId,
              :mode,
              'COMPLETED',
              :listeningCorrect,
              :readingCorrect,
              :listeningScore,
              :readingScore,
              now(),
              now()
            )
            """, new MapSqlParameterSource()
            .addValue("id", attemptId)
            .addValue("learnerId", learnerId)
            .addValue("testId", testId)
            .addValue("mode", mode)
            .addValue("listeningCorrect", score.listeningCorrect())
            .addValue("readingCorrect", score.readingCorrect())
            .addValue("listeningScore", score.listeningScaledScore())
            .addValue("readingScore", score.readingScaledScore()));

        List<MapSqlParameterSource> rows = new ArrayList<>(answerKey.size());
        for (QuestionKey key : answerKey) {
            String answer = submittedAnswers.getOrDefault(key.questionId(), "");
            boolean correct = key.correctAnswer() != null
                && key.correctAnswer().equalsIgnoreCase(answer);
            rows.add(new MapSqlParameterSource()
                .addValue("attemptId", attemptId)
                .addValue("questionId", key.questionId())
                .addValue("answer", answer.isBlank() ? null : answer)
                .addValue("correct", correct));
        }

        if (!rows.isEmpty()) {
            jdbc.batchUpdate("""
                INSERT INTO toeic_attempt_answer(
                  attempt_id,
                  question_id,
                  answer,
                  correct,
                  answered_at
                ) VALUES (
                  :attemptId,
                  :questionId,
                  :answer,
                  :correct,
                  now()
                )
                """, rows.toArray(MapSqlParameterSource[]::new));
        }
        return attemptId;
    }
}
