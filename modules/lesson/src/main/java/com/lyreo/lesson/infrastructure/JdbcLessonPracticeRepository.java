package com.lyreo.lesson.infrastructure;

import com.lyreo.lesson.application.LessonPracticeRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** JDBC adapter for append-only Lesson attempts and compact Lesson progress projections. */
public final class JdbcLessonPracticeRepository implements LessonPracticeRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcLessonPracticeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<DictationTarget> findDictationTarget(
        UUID lessonId,
        UUID activityId,
        UUID sentenceId
    ) {
        return jdbc.query("""
            SELECT a.lesson_id,
                   a.id AS activity_id,
                   s.id AS sentence_id,
                   s.text AS expected_text
              FROM lesson_activity a
              JOIN lesson_sentence s ON s.lesson_id = a.lesson_id
             WHERE a.lesson_id = :lessonId
               AND a.id = :activityId
               AND a.activity_type = 'DICTATION'
               AND a.enabled = TRUE
               AND s.id = :sentenceId
            """, Map.of(
                "lessonId", lessonId,
                "activityId", activityId,
                "sentenceId", sentenceId
            ), (rs, rowNum) -> new DictationTarget(
                rs.getObject("lesson_id", UUID.class),
                rs.getObject("activity_id", UUID.class),
                rs.getObject("sentence_id", UUID.class),
                rs.getString("expected_text")
            ))
            .stream()
            .findFirst();
    }

    @Override
    public UUID appendDictationAttempt(
        UUID learnerId,
        DictationTarget target,
        String answer,
        int serverScore,
        String detailJson
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO lesson_practice_attempt(
              id,
              learner_id,
              lesson_id,
              activity_id,
              sentence_id,
              activity_type,
              server_score,
              answer_text,
              detail_json,
              created_at
            ) VALUES (
              :id,
              :learnerId,
              :lessonId,
              :activityId,
              :sentenceId,
              'DICTATION',
              :serverScore,
              :answer,
              CAST(:detailJson AS jsonb),
              now()
            )
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("learnerId", learnerId)
            .addValue("lessonId", target.lessonId())
            .addValue("activityId", target.activityId())
            .addValue("sentenceId", target.sentenceId())
            .addValue("serverScore", serverScore)
            .addValue("answer", answer)
            .addValue("detailJson", detailJson));
        return id;
    }

    @Override
    public ActivityProgress refreshActivityProgress(
        UUID learnerId,
        UUID lessonId,
        UUID activityId,
        int completionScore
    ) {
        jdbc.update("""
            INSERT INTO lesson_progress(
              learner_id,
              lesson_id,
              status,
              started_at,
              last_activity_at
            ) VALUES (
              :learnerId,
              :lessonId,
              'IN_PROGRESS',
              now(),
              now()
            )
            ON CONFLICT(learner_id, lesson_id) DO UPDATE SET
              status = CASE
                WHEN lesson_progress.status = 'COMPLETED' THEN 'COMPLETED'
                ELSE 'IN_PROGRESS'
              END,
              started_at = COALESCE(lesson_progress.started_at, now()),
              last_activity_at = now()
            """, Map.of("learnerId", learnerId, "lessonId", lessonId));

        return jdbc.queryForObject("""
            WITH sentence_best AS (
                SELECT s.id,
                       MAX(a.server_score) AS best_score
                  FROM lesson_sentence s
                  LEFT JOIN lesson_practice_attempt a
                    ON a.sentence_id = s.id
                   AND a.activity_id = :activityId
                   AND a.learner_id = :learnerId
                 WHERE s.lesson_id = :lessonId
                 GROUP BY s.id
            ), stats AS (
                SELECT COUNT(*)::int AS total_items,
                       COUNT(*) FILTER (WHERE best_score >= :completionScore)::int AS completed_items,
                       ROUND(AVG(best_score) FILTER (WHERE best_score IS NOT NULL))::int AS best_score
                  FROM sentence_best
            ), upserted AS (
                INSERT INTO lesson_activity_progress(
                  learner_id,
                  activity_id,
                  status,
                  completed_items,
                  total_items,
                  best_score,
                  started_at,
                  last_practiced_at
                )
                SELECT :learnerId,
                       :activityId,
                       'IN_PROGRESS',
                       completed_items,
                       total_items,
                       best_score,
                       now(),
                       now()
                  FROM stats
                ON CONFLICT(learner_id, activity_id) DO UPDATE SET
                  completed_items = excluded.completed_items,
                  total_items = excluded.total_items,
                  best_score = GREATEST(
                    COALESCE(lesson_activity_progress.best_score, 0),
                    COALESCE(excluded.best_score, 0)
                  ),
                  last_practiced_at = now()
                RETURNING completed_items, total_items, best_score
            )
            SELECT completed_items, total_items, best_score
              FROM upserted
            """, new MapSqlParameterSource()
            .addValue("learnerId", learnerId)
            .addValue("lessonId", lessonId)
            .addValue("activityId", activityId)
            .addValue("completionScore", completionScore),
            (rs, rowNum) -> new ActivityProgress(
                rs.getInt("completed_items"),
                rs.getInt("total_items"),
                rs.getObject("best_score", Integer.class)
            ));
    }

    @Override
    public boolean tryMarkActivityCompleted(UUID learnerId, UUID activityId) {
        return jdbc.update("""
            UPDATE lesson_activity_progress
               SET status = 'COMPLETED',
                   completed_at = now(),
                   last_practiced_at = now()
             WHERE learner_id = :learnerId
               AND activity_id = :activityId
               AND completed_at IS NULL
               AND total_items > 0
               AND completed_items >= total_items
            """, Map.of(
                "learnerId", learnerId,
                "activityId", activityId
            )) == 1;
    }

    @Override
    public boolean tryMarkLessonCompleted(UUID learnerId, UUID lessonId) {
        return jdbc.update("""
            UPDATE lesson_progress p
               SET status = 'COMPLETED',
                   completed_at = now(),
                   last_activity_at = now()
             WHERE p.learner_id = :learnerId
               AND p.lesson_id = :lessonId
               AND p.status <> 'COMPLETED'
               AND EXISTS (
                    SELECT 1
                      FROM lesson_activity a
                     WHERE a.lesson_id = :lessonId
                       AND a.enabled = TRUE
               )
               AND NOT EXISTS (
                    SELECT 1
                      FROM lesson_activity a
                      LEFT JOIN lesson_activity_progress ap
                        ON ap.activity_id = a.id
                       AND ap.learner_id = :learnerId
                     WHERE a.lesson_id = :lessonId
                       AND a.enabled = TRUE
                       AND (ap.status IS NULL OR ap.status <> 'COMPLETED')
               )
            """, Map.of(
                "learnerId", learnerId,
                "lessonId", lessonId
            )) == 1;
    }
}
