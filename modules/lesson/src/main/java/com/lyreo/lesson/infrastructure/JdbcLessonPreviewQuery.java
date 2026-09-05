package com.lyreo.lesson.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyreo.lesson.application.LessonPreviewQuery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Read-only projection optimized for lesson preview/debugging. */
public final class JdbcLessonPreviewQuery implements LessonPreviewQuery {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcLessonPreviewQuery(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Optional<Map<String, Object>> find(UUID lessonId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id,title,source_type,source_text,source_reference,canonical_audio_object_key,status,created_at,updated_at
              FROM lesson WHERE id=:id
            """, Map.of("id", lessonId));
        if (rows.isEmpty()) return Optional.empty();

        Map<String, Object> result = new LinkedHashMap<>(rows.getFirst());
        List<Map<String, Object>> sentences = jdbc.queryForList("""
            SELECT id,position,text,audio_start_ms,audio_end_ms,audio_clip_object_key
              FROM lesson_sentence WHERE lesson_id=:id ORDER BY position
            """, Map.of("id", lessonId));

        List<Map<String, Object>> sentenceViews = new ArrayList<>();
        for (Map<String, Object> sentence : sentences) {
            UUID sentenceId = (UUID) sentence.get("id");
            Map<String, Object> view = new LinkedHashMap<>(sentence);
            view.put("words", jdbc.queryForList("""
                SELECT position,surface_text,start_ms,end_ms
                  FROM lesson_word_timestamp WHERE sentence_id=:sentence ORDER BY position
                """, Map.of("sentence", sentenceId)));
            List<Map<String, Object>> annotations = jdbc.queryForList("""
                SELECT annotation_type,payload_json::text payload_json,generated_by,provider,model,status,created_at
                  FROM lesson_annotation WHERE sentence_id=:sentence ORDER BY created_at,annotation_type
                """, Map.of("sentence", sentenceId));
            annotations.forEach(this::decodePayload);
            view.put("annotations", annotations);
            sentenceViews.add(view);
        }
        result.put("sentences", sentenceViews);
        result.put("activities", jdbc.queryForList("""
            SELECT id,activity_type,position,enabled,config_json::text config_json
              FROM lesson_activity WHERE lesson_id=:id ORDER BY position
            """, Map.of("id", lessonId)));
        result.put("buildJobs", jdbc.queryForList("""
            SELECT j.job_id,b.status,b.current_step,b.progress_percent,b.attempt_count,b.error_message,j.created_at
              FROM lesson_build_job j JOIN background_job b ON b.id=j.job_id
             WHERE j.lesson_id=:id ORDER BY j.created_at DESC
            """, Map.of("id", lessonId)));
        return Optional.of(result);
    }

    private void decodePayload(Map<String, Object> row) {
        Object raw = row.get("payload_json");
        if (!(raw instanceof String json)) return;
        try {
            row.put("payload", mapper.readTree(json));
            row.remove("payload_json");
        } catch (Exception ignored) {
            // Keep the original text if legacy/corrupt data cannot be decoded.
        }
    }
}
