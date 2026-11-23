package com.lyreo.speechassessment.infrastructure.persistence;

import com.lyreo.lesson.api.ShadowingReference;
import com.lyreo.speechassessment.application.AdminPronunciationAttempts.Attempt;
import com.lyreo.speechassessment.application.AdminPronunciationAttempts.Store;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcAdminPronunciationAttempts implements Store {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcAdminPronunciationAttempts(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void reserve(UUID id, String subject, UUID key, String fingerprint, UUID lesson, UUID sentence, long revision) {
        jdbc.update("""
            INSERT INTO admin_pronunciation_attempt
            (id, admin_subject, idempotency_key, fingerprint, lesson_id, sentence_id, revision)
            VALUES (:id, :subject, :key, :fingerprint, :lesson, :sentence, :revision)
            ON CONFLICT (admin_subject, idempotency_key) DO NOTHING
            """, Map.of("id", id, "subject", subject, "key", key, "fingerprint", fingerprint,
                        "lesson", lesson, "sentence", sentence, "revision", revision));
    }

    @Override
    public Optional<Attempt> byKey(String subject, UUID key, boolean lock) {
        return jdbc.query("""
            SELECT * FROM admin_pronunciation_attempt WHERE admin_subject = :subject AND idempotency_key = :key
            """ + (lock ? " FOR UPDATE" : ""), Map.of("subject", subject, "key", key), this::row).stream().findFirst();
    }

    @Override
    public Optional<Attempt> active(UUID id) {
        return jdbc.query("""
            SELECT * FROM admin_pronunciation_attempt
            WHERE id = :id AND expires_at > now() AND job_id IS NOT NULL
            """, Map.of("id", id), this::row).stream().findFirst();
    }

    @Override
    public Attempt accept(UUID id, UUID job, ShadowingReference.Sentence reference) {
        return jdbc.queryForObject("""
            UPDATE admin_pronunciation_attempt
               SET job_id = :job, reference_text = :text, reference_start_ms = :start, reference_end_ms = :end,
                   expires_at = now() + interval '24 hours'
             WHERE id = :id
            RETURNING *
            """, Map.of("id", id, "job", job, "text", reference.text(), "start", reference.startMs(),
                        "end", reference.endMs()), this::row);
    }

    @Override
    public boolean complete(UUID id, UUID invocationId, JsonNode result) {
        return jdbc.update("""
            UPDATE admin_pronunciation_attempt SET invocation_id = :invocation, result_json = CAST(:result AS jsonb)
             WHERE id = :id AND expires_at > now()
            """, Map.of("id", id, "invocation", invocationId, "result", mapper.writeValueAsString(result))) == 1;
    }

    @Override
    public List<UUID> expired() {
        return jdbc.queryForList("""
            SELECT id FROM admin_pronunciation_attempt WHERE expires_at <= now()
            ORDER BY expires_at LIMIT 100
            """, Map.of(), UUID.class);
    }

    @Override
    public boolean lockExpired(UUID id) {
        return !jdbc.queryForList("""
            SELECT id FROM admin_pronunciation_attempt WHERE id = :id AND expires_at <= now()
            FOR UPDATE SKIP LOCKED
            """, Map.of("id", id), UUID.class).isEmpty();
    }

    @Override
    public void delete(UUID id) {
        jdbc.update("DELETE FROM admin_pronunciation_attempt WHERE id = :id", Map.of("id", id));
    }

    private Attempt row(ResultSet row, int index) throws SQLException {
        String result = row.getString("result_json");
        return new Attempt(row.getObject("id", UUID.class), row.getString("admin_subject"), row.getString("fingerprint"),
            row.getObject("lesson_id", UUID.class), row.getObject("sentence_id", UUID.class), row.getLong("revision"),
            row.getObject("job_id", UUID.class), row.getString("reference_text"),
            row.getTimestamp("expires_at").toInstant(), result == null ? null : mapper.readTree(result));
    }
}
