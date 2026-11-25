package com.lyreo.speechassessment.infrastructure.persistence;

import com.lyreo.lesson.api.ShadowingReference;
import com.lyreo.speechassessment.application.LearnerPronunciationAttempts.Attempt;
import com.lyreo.speechassessment.application.LearnerPronunciationAttempts.Store;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** JDBC persistence adapter for learner Shadowing pronunciation attempts. */
@Repository
public class JdbcLearnerPronunciationAttempts implements Store {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcLearnerPronunciationAttempts(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void reserve(
        UUID id,
        UUID learnerId,
        UUID key,
        String fingerprint,
        UUID lessonId,
        UUID activityId,
        UUID sentenceId,
        long revision
    ) {
        jdbc.update("""
            INSERT INTO learner_pronunciation_attempt (
                id, learner_id, idempotency_key, fingerprint, lesson_id, activity_id, sentence_id, revision
            ) VALUES (
                :id, :learnerId, :key, :fingerprint, :lessonId, :activityId, :sentenceId, :revision
            )
            ON CONFLICT (learner_id, idempotency_key) DO NOTHING
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("learnerId", learnerId)
            .addValue("key", key)
            .addValue("fingerprint", fingerprint)
            .addValue("lessonId", lessonId)
            .addValue("activityId", activityId)
            .addValue("sentenceId", sentenceId)
            .addValue("revision", revision));
    }

    @Override
    public Optional<Attempt> byKey(UUID learnerId, UUID key, boolean lock) {
        return jdbc.query("""
            SELECT *
              FROM learner_pronunciation_attempt
             WHERE learner_id = :learnerId
               AND idempotency_key = :key
            """ + (lock ? " FOR UPDATE" : ""),
            Map.of("learnerId", learnerId, "key", key),
            this::row
        ).stream().findFirst();
    }

    @Override
    public Optional<Attempt> byId(UUID id) {
        return jdbc.query(
            "SELECT * FROM learner_pronunciation_attempt WHERE id = :id",
            Map.of("id", id),
            this::row
        ).stream().findFirst();
    }

    @Override
    public Attempt accept(
        UUID id,
        UUID jobId,
        UUID reservationId,
        String recordingObjectKey,
        ShadowingReference.LearnerSentence reference
    ) {
        return jdbc.queryForObject("""
            UPDATE learner_pronunciation_attempt
               SET job_id = :jobId,
                   reservation_id = :reservationId,
                   recording_object_key = :recordingObjectKey,
                   reference_text = :referenceText,
                   reference_start_ms = :startMs,
                   reference_end_ms = :endMs,
                   expires_at = now() + interval '24 hours'
             WHERE id = :id
            RETURNING *
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("jobId", jobId)
            .addValue("reservationId", reservationId)
            .addValue("recordingObjectKey", recordingObjectKey)
            .addValue("referenceText", reference.text())
            .addValue("startMs", reference.startMs())
            .addValue("endMs", reference.endMs()),
            this::row);
    }

    @Override
    public boolean complete(UUID id, UUID invocationId, String outcome, Integer overallScore, JsonNode result) {
        return jdbc.update("""
            UPDATE learner_pronunciation_attempt
               SET invocation_id = :invocationId,
                   outcome = :outcome,
                   overall_score = :overallScore,
                   result_json = CAST(:resultJson AS jsonb)
             WHERE id = :id
               AND expires_at > now()
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("invocationId", invocationId)
            .addValue("outcome", outcome)
            .addValue("overallScore", overallScore)
            .addValue("resultJson", mapper.writeValueAsString(result))) == 1;
    }

    @Override
    public Optional<UUID> findReservationIdByJobId(UUID jobId) {
        return jdbc.query(
            "SELECT reservation_id FROM learner_pronunciation_attempt WHERE job_id = :jobId AND reservation_id IS NOT NULL",
            Map.of("jobId", jobId),
            (rs, rowNum) -> rs.getObject("reservation_id", UUID.class)
        ).stream().findFirst();
    }

    @Override
    public List<UUID> expiredAudioIds() {
        return jdbc.queryForList("""
            SELECT id
              FROM learner_pronunciation_attempt
             WHERE expires_at <= now()
               AND (recording_purged_at IS NULL OR job_id IS NULL)
             ORDER BY expires_at
             LIMIT 100
            """, Map.of(), UUID.class);
    }

    @Override
    public Optional<Attempt> lockForPurge(UUID id) {
        return jdbc.query("""
            SELECT *
              FROM learner_pronunciation_attempt
             WHERE id = :id
               AND expires_at <= now()
               AND (recording_purged_at IS NULL OR job_id IS NULL)
             FOR UPDATE SKIP LOCKED
            """, Map.of("id", id), this::row).stream().findFirst();
    }

    @Override
    public void markRecordingPurged(UUID id) {
        jdbc.update("""
            UPDATE learner_pronunciation_attempt
               SET recording_purged_at = now(),
                   recording_object_key = NULL
             WHERE id = :id
            """, Map.of("id", id));
    }

    @Override
    public void delete(UUID id) {
        jdbc.update("DELETE FROM learner_pronunciation_attempt WHERE id = :id", Map.of("id", id));
    }

    private Attempt row(ResultSet rs, int index) throws SQLException {
        String result = rs.getString("result_json");
        return new Attempt(
            rs.getObject("id", UUID.class),
            rs.getObject("learner_id", UUID.class),
            rs.getString("fingerprint"),
            rs.getObject("lesson_id", UUID.class),
            rs.getObject("activity_id", UUID.class),
            rs.getObject("sentence_id", UUID.class),
            rs.getLong("revision"),
            rs.getObject("job_id", UUID.class),
            rs.getObject("reservation_id", UUID.class),
            rs.getString("recording_object_key"),
            toInstant(rs.getTimestamp("recording_purged_at")),
            toInstant(rs.getTimestamp("expires_at")),
            rs.getString("reference_text"),
            rs.getString("outcome"),
            rs.getObject("overall_score", Integer.class),
            result == null ? null : mapper.readTree(result),
            toInstant(rs.getTimestamp("created_at"))
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
