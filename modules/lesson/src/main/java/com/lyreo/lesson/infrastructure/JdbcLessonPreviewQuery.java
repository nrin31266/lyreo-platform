package com.lyreo.lesson.infrastructure;

import tools.jackson.databind.ObjectMapper;
import com.lyreo.lesson.application.LessonPreviewQuery;
import com.lyreo.lesson.application.LessonPreviewView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Read-only projection querying lesson-owned tables for preview. */
public final class JdbcLessonPreviewQuery implements LessonPreviewQuery {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcLessonPreviewQuery(
        NamedParameterJdbcTemplate jdbc,
        ObjectMapper mapper
    ) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Optional<LessonPreviewView> find(UUID lessonId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id,title,source_type,source_text,source_reference,canonical_audio_object_key,status,created_at,updated_at
              FROM lesson WHERE id=:id
            """, Map.of("id", lessonId));
        if (rows.isEmpty()) return Optional.empty();

        Map<String, Object> root = rows.getFirst();

        List<Map<String, Object>> sentenceRows = jdbc.queryForList("""
            SELECT id,position,text,audio_start_ms,audio_end_ms,audio_clip_object_key
              FROM lesson_sentence WHERE lesson_id=:id ORDER BY position
            """, Map.of("id", lessonId));

        List<LessonPreviewView.SentenceView> sentenceViews = new ArrayList<>();
        for (Map<String, Object> s : sentenceRows) {
            UUID sentenceId = (UUID) s.get("id");

            List<Map<String, Object>> wordRows = jdbc.queryForList("""
                SELECT position,surface_text,start_ms,end_ms
                  FROM lesson_word_timestamp WHERE sentence_id=:sentence ORDER BY position
                """, Map.of("sentence", sentenceId));
            List<LessonPreviewView.WordView> wordViews = new ArrayList<>();
            for (Map<String, Object> w : wordRows) {
                wordViews.add(new LessonPreviewView.WordView(
                    ((Number) w.get("position")).intValue(),
                    (String) w.get("surface_text"),
                    w.get("start_ms") instanceof Number n ? n.intValue() : null,
                    w.get("end_ms") instanceof Number n ? n.intValue() : null
                ));
            }

            List<Map<String, Object>> annotationRows = jdbc.queryForList("""
                SELECT annotation_type,payload_json::text payload_json,generated_by,provider,model,status,created_at
                  FROM lesson_annotation WHERE sentence_id=:sentence ORDER BY created_at,annotation_type
                """, Map.of("sentence", sentenceId));
            List<LessonPreviewView.AnnotationView> annotationViews = new ArrayList<>();
            for (Map<String, Object> a : annotationRows) {
                annotationViews.add(new LessonPreviewView.AnnotationView(
                    (String) a.get("annotation_type"),
                    decodePayload(a.get("payload_json")),
                    (String) a.get("generated_by"),
                    (String) a.get("provider"),
                    (String) a.get("model"),
                    (String) a.get("status"),
                    toInstant(a.get("created_at"))
                ));
            }

            sentenceViews.add(new LessonPreviewView.SentenceView(
                sentenceId,
                ((Number) s.get("position")).intValue(),
                (String) s.get("text"),
                s.get("audio_start_ms") instanceof Number n ? n.intValue() : null,
                s.get("audio_end_ms") instanceof Number n ? n.intValue() : null,
                (String) s.get("audio_clip_object_key"),
                wordViews,
                annotationViews
            ));
        }

        List<Map<String, Object>> activityRows = jdbc.queryForList("""
            SELECT id,activity_type,position,enabled,config_json::text config_json
              FROM lesson_activity WHERE lesson_id=:id ORDER BY position
            """, Map.of("id", lessonId));
        List<LessonPreviewView.ActivityView> activityViews = new ArrayList<>();
        for (Map<String, Object> act : activityRows) {
            activityViews.add(new LessonPreviewView.ActivityView(
                (UUID) act.get("id"),
                (String) act.get("activity_type"),
                ((Number) act.get("position")).intValue(),
                Boolean.TRUE.equals(act.get("enabled")),
                (String) act.get("config_json")
            ));
        }

        // Fetch lesson-owned build job links. Status enrichment is handled at application layer by LessonPreviewService.
        List<Map<String, Object>> buildJobLinks = jdbc.queryForList("""
            SELECT job_id,created_at FROM lesson_build_job WHERE lesson_id=:id ORDER BY created_at DESC
            """, Map.of("id", lessonId));
        List<LessonPreviewView.BuildJobView> buildJobs = new ArrayList<>();
        for (Map<String, Object> link : buildJobLinks) {
            UUID jobId = (UUID) link.get("job_id");
            Instant createdAt = toInstant(link.get("created_at"));
            buildJobs.add(new LessonPreviewView.BuildJobView(
                jobId,
                createdAt,
                null,
                null,
                null,
                null,
                null
            ));
        }

        return Optional.of(new LessonPreviewView(
            (UUID) root.get("id"),
            (String) root.get("title"),
            (String) root.get("source_type"),
            (String) root.get("source_text"),
            (String) root.get("source_reference"),
            (String) root.get("canonical_audio_object_key"),
            (String) root.get("status"),
            toInstant(root.get("created_at")),
            toInstant(root.get("updated_at")),
            sentenceViews,
            activityViews,
            buildJobs
        ));
    }

    private Object decodePayload(Object raw) {
        if (!(raw instanceof String json)) return raw;
        try {
            return mapper.readTree(json);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant i) return i;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (value instanceof String s) return Instant.parse(s);
        return null;
    }
}
