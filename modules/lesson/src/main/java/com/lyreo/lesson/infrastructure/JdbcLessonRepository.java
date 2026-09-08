package com.lyreo.lesson.infrastructure;

import com.lyreo.lesson.application.LessonRepository;
import com.lyreo.lesson.domain.Lesson;
import com.lyreo.lesson.domain.LessonSentence;
import com.lyreo.lesson.domain.LessonSourceType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcLessonRepository implements LessonRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcLessonRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Lesson createDraft(String title, String sourceType, String sourceText, String sourceReference) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO lesson(id, title, source_type, source_text, source_reference, status, created_at)
            VALUES (:id, :title, :sourceType, :sourceText, :sourceReference, 'DRAFT', now())
            """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("title", title)
                .addValue("sourceType", sourceType)
                .addValue("sourceText", sourceText)
                .addValue("sourceReference", sourceReference));
        return findById(id).orElseThrow();
    }

    @Override
    public Optional<Lesson> findById(UUID lessonId) {
        return jdbc.query("""
            SELECT id, title, source_type, source_text, source_reference,
                   canonical_audio_object_key, status, created_at
              FROM lesson WHERE id = :id
            """, Map.of("id", lessonId), this::mapLesson).stream().findFirst();
    }

    @Override
    public List<LessonSentence> sentences(UUID lessonId) {
        return jdbc.query("""
            SELECT id, lesson_id, position, text, audio_start_ms, audio_end_ms,
                   audio_clip_object_key
              FROM lesson_sentence
             WHERE lesson_id = :lessonId
             ORDER BY position
            """, Map.of("lessonId", lessonId), (rs, rowNum) -> new LessonSentence(
                rs.getObject("id", UUID.class),
                rs.getObject("lesson_id", UUID.class),
                rs.getInt("position"),
                rs.getString("text"),
                nullableLong(rs, "audio_start_ms"),
                nullableLong(rs, "audio_end_ms"),
                rs.getString("audio_clip_object_key")
            ));
    }

    @Override
    public void replaceSentences(UUID lessonId, List<String> sentences) {
        jdbc.update("DELETE FROM lesson_sentence WHERE lesson_id = :lessonId", Map.of("lessonId", lessonId));
        int position = 0;
        for (String sentence : sentences) {
            jdbc.update("""
                INSERT INTO lesson_sentence(id, lesson_id, position, text)
                VALUES (:id, :lessonId, :position, :text)
                """, Map.of(
                    "id", UUID.randomUUID(),
                    "lessonId", lessonId,
                    "position", position++,
                    "text", sentence
                ));
        }
    }

    @Override
    public void setCanonicalAudio(UUID lessonId, String objectKey) {
        jdbc.update("""
            UPDATE lesson SET canonical_audio_object_key = :key WHERE id = :id
            """, Map.of("id", lessonId, "key", objectKey));
    }

    @Override
    public void markProcessing(UUID lessonId) {
        updateStatus(lessonId, "PROCESSING");
    }

    @Override
    public void markReady(UUID lessonId) {
        updateStatus(lessonId, "READY");
    }

    @Override
    public void markFailed(UUID lessonId) {
        updateStatus(lessonId, "FAILED");
    }

    @Override
    public void markCancelled(UUID lessonId) {
        updateStatus(lessonId, "CANCELLED");
    }

    private void updateStatus(UUID lessonId, String status) {
        jdbc.update(
            "UPDATE lesson SET status = :status, updated_at = now() WHERE id = :id",
            Map.of("id", lessonId, "status", status)
        );
    }

    private Lesson mapLesson(ResultSet rs, int rowNum) throws SQLException {
        return new Lesson(
            rs.getObject("id", UUID.class),
            rs.getString("title"),
            LessonSourceType.valueOf(rs.getString("source_type")),
            rs.getString("source_text"),
            rs.getString("source_reference"),
            rs.getString("canonical_audio_object_key"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant()
        );
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
