package com.lyreo.toeic.infrastructure.persistence;

import com.lyreo.toeic.application.port.ToeicAttemptRepository;
import com.lyreo.toeic.application.port.ToeicAttemptRepository.QuestionKey;
import com.lyreo.toeic.application.port.ToeicAttemptRepository.TestAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** PostgreSQL adapter over active TOEIC versions, placements and shared assessment items. */
public final class JdbcToeicAttemptRepository implements ToeicAttemptRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcToeicAttemptRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TestAccess> findActiveTest(UUID catalogId) {
        return jdbc.query(
            """
            SELECT tc.id AS catalog_id,
                   tv.id AS test_version_id,
                   tc.publication_status,
                   tc.access_mode,
                   tc.required_feature_key
              FROM dataset_active_release ar
              JOIN toeic_test_version tv ON tv.release_id = ar.release_id
              JOIN toeic_test_catalog tc ON tc.id = tv.catalog_id
             WHERE ar.domain = 'grammar-toeic'
               AND tc.id = :catalogId
            """,
            Map.of("catalogId", catalogId),
            (rs, rowNum) -> new TestAccess(
                rs.getObject("catalog_id", UUID.class),
                rs.getObject("test_version_id", UUID.class),
                rs.getString("publication_status"),
                rs.getString("access_mode"),
                rs.getString("required_feature_key")
            )
        ).stream().findFirst();
    }

    @Override
    public List<QuestionKey> answerKey(UUID testVersionId, Set<UUID> placementIds) {
        if (placementIds == null || placementIds.isEmpty()) {
            return jdbc.query("""
                SELECT p.id AS placement_id, p.part, ai.correct_option
                  FROM toeic_placement p
                  JOIN assessment_item ai ON ai.id = p.item_id AND ai.release_id = p.release_id
                 WHERE p.test_version_id = :testVersionId
                 ORDER BY p.question_number, p.id
                """, Map.of("testVersionId", testVersionId), JdbcToeicAttemptRepository::mapQuestionKey);
        }

        return jdbc.query("""
            SELECT p.id AS placement_id, p.part, ai.correct_option
              FROM toeic_placement p
              JOIN assessment_item ai ON ai.id = p.item_id AND ai.release_id = p.release_id
             WHERE p.test_version_id = :testVersionId
               AND p.id IN (:placementIds)
             ORDER BY p.question_number, p.id
            """, new MapSqlParameterSource()
            .addValue("testVersionId", testVersionId)
            .addValue("placementIds", placementIds), JdbcToeicAttemptRepository::mapQuestionKey);
    }

    @Override
    public UUID saveCompletedAttempt(
        UUID learnerId,
        UUID testVersionId,
        String mode,
        ScoreSummary score,
        Map<UUID, String> submittedAnswers,
        List<QuestionKey> answerKey
    ) {
        UUID attemptId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO toeic_attempt(
              id, learner_id, test_version_id, mode, status,
              raw_listening_correct, raw_reading_correct,
              listening_score, reading_score, started_at, submitted_at
            ) VALUES (
              :id, :learnerId, :testVersionId, :mode, 'COMPLETED',
              :listeningCorrect, :readingCorrect, :listeningScore, :readingScore, now(), now()
            )
            """, new MapSqlParameterSource()
            .addValue("id", attemptId)
            .addValue("learnerId", learnerId)
            .addValue("testVersionId", testVersionId)
            .addValue("mode", mode)
            .addValue("listeningCorrect", score.listeningCorrect())
            .addValue("readingCorrect", score.readingCorrect())
            .addValue("listeningScore", score.listeningScaledScore())
            .addValue("readingScore", score.readingScaledScore()));

        List<MapSqlParameterSource> rows = new ArrayList<>(answerKey.size());
        for (QuestionKey key : answerKey) {
            String answer = submittedAnswers.getOrDefault(key.placementId(), "");
            boolean correct = key.correctAnswer() != null
                && key.correctAnswer().equalsIgnoreCase(answer);
            rows.add(new MapSqlParameterSource()
                .addValue("attemptId", attemptId)
                .addValue("placementId", key.placementId())
                .addValue("testVersionId", testVersionId)
                .addValue("answer", answer.isBlank() ? null : answer)
                .addValue("correct", correct));
        }

        if (!rows.isEmpty()) {
            jdbc.batchUpdate("""
                INSERT INTO toeic_attempt_answer(
                  attempt_id, placement_id, test_version_id, answer, correct, answered_at
                ) VALUES (
                  :attemptId, :placementId, :testVersionId, :answer, :correct, now()
                )
                """, rows.toArray(MapSqlParameterSource[]::new));
        }
        return attemptId;
    }

    private static QuestionKey mapQuestionKey(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new QuestionKey(
            rs.getObject("placement_id", UUID.class),
            rs.getObject("part", Integer.class),
            rs.getString("correct_option")
        );
    }
}
