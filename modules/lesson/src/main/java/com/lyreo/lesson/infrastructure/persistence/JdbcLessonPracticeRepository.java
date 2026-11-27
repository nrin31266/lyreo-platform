package com.lyreo.lesson.infrastructure.persistence;

import com.lyreo.lesson.application.port.LessonPracticeRepository;
import java.util.List;
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
    public Optional<LessonAccess> findLessonAccess(UUID lessonId) {
        return jdbc.query("""
            SELECT m.access_mode, m.required_feature_key
              FROM lesson_metadata m
             WHERE m.lesson_id = :lessonId
               AND m.deleted_at IS NULL
            """, Map.of("lessonId", lessonId),
            (rs, rowNum) -> new LessonAccess(rs.getString("access_mode"), rs.getString("required_feature_key"))
        ).stream().findFirst();
    }

    @Override
    public List<UUID> findCompletedSentenceIds(UUID learnerId, UUID activityId, int completionScore) {
        return jdbc.query("""
            SELECT a.sentence_id
              FROM lesson_practice_attempt a
             WHERE a.learner_id = :learnerId
               AND a.activity_id = :activityId
             GROUP BY a.sentence_id
            HAVING MAX(a.server_score) >= :completionScore
            """,
            Map.of("learnerId", learnerId, "activityId", activityId, "completionScore", completionScore),
            (rs, rowNum) -> rs.getObject("sentence_id", UUID.class)
        );
    }

    @Override
    public List<UUID> findActivitySentenceIds(UUID lessonId, UUID activityId) {
        return jdbc.query("""
            SELECT s.id
              FROM lesson_sentence s
             WHERE s.lesson_id = :lessonId
               AND (NOT EXISTS(SELECT 1 FROM lesson_authoring x WHERE x.lesson_id = s.lesson_id)
                    OR EXISTS(SELECT 1 FROM lesson_authoring x JOIN lesson_activity_sentence m
                        ON m.activity_id = :activityId AND m.sentence_id = s.id
                        WHERE x.lesson_id = s.lesson_id AND x.published_at IS NOT NULL))
             ORDER BY s.position ASC
            """,
            Map.of("lessonId", lessonId, "activityId", activityId),
            (rs, rowNum) -> rs.getObject("id", UUID.class)
        );
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
               AND EXISTS(SELECT 1 FROM lesson_availability v WHERE v.lesson_id=a.lesson_id AND v.available)
               AND s.id = :sentenceId
               AND (NOT EXISTS(SELECT 1 FROM lesson_authoring x WHERE x.lesson_id=a.lesson_id)
                    OR EXISTS(SELECT 1 FROM lesson_authoring x JOIN lesson_activity_sentence m
                        ON m.activity_id=a.id AND m.sentence_id=s.id
                        WHERE x.lesson_id=a.lesson_id AND x.published_at IS NOT NULL))
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
    public boolean isLessonAvailable(UUID lessonId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM lesson_availability v WHERE v.lesson_id = :lessonId AND v.available)",
            Map.of("lessonId", lessonId),
            Boolean.class
        ));
    }

    @Override
    public Optional<ShadowingTarget> findShadowingTarget(
        UUID lessonId,
        UUID activityId,
        UUID sentenceId
    ) {
        return jdbc.query("""
            SELECT a.lesson_id,
                   a.id AS activity_id,
                   s.id AS sentence_id,
                   s.text AS expected_text,
                   s.audio_start_ms,
                   s.audio_end_ms,
                   (l.canonical_audio_object_key IS NOT NULL OR x.video_id IS NOT NULL) AS playable
              FROM lesson_activity a
              JOIN lesson l ON l.id = a.lesson_id
              LEFT JOIN lesson_authoring x ON x.lesson_id = l.id
              JOIN lesson_sentence s ON s.lesson_id = a.lesson_id
             WHERE a.lesson_id = :lessonId
               AND a.id = :activityId
               AND a.activity_type = 'SHADOWING'
               AND a.enabled = TRUE
               AND EXISTS(SELECT 1 FROM lesson_availability v WHERE v.lesson_id = a.lesson_id AND v.available)
               AND s.id = :sentenceId
               AND (x.lesson_id IS NULL
                    OR (x.published_at IS NOT NULL AND EXISTS(
                        SELECT 1 FROM lesson_activity_sentence m
                         WHERE m.activity_id = a.id AND m.sentence_id = s.id)))
            """, Map.of(
                "lessonId", lessonId,
                "activityId", activityId,
                "sentenceId", sentenceId
            ), (rs, rowNum) -> new ShadowingTarget(
                rs.getObject("lesson_id", UUID.class),
                rs.getObject("activity_id", UUID.class),
                rs.getObject("sentence_id", UUID.class),
                rs.getString("expected_text"),
                rs.getObject("audio_start_ms", Long.class),
                rs.getObject("audio_end_ms", Long.class),
                rs.getBoolean("playable")
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
    public void appendShadowingAttempt(
        UUID attemptId,
        UUID learnerId,
        UUID lessonId,
        UUID activityId,
        UUID sentenceId,
        int serverScore,
        String transcript,
        String detailJson
    ) {
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
              'SHADOWING',
              :serverScore,
              :transcript,
              CAST(COALESCE(:detailJson, '{}') AS jsonb),
              now()
            )
            ON CONFLICT (id) DO NOTHING
            """, new MapSqlParameterSource()
            .addValue("id", attemptId)
            .addValue("learnerId", learnerId)
            .addValue("lessonId", lessonId)
            .addValue("activityId", activityId)
            .addValue("sentenceId", sentenceId)
            .addValue("serverScore", Math.clamp(serverScore, 0, 100))
            .addValue("transcript", transcript)
            .addValue("detailJson", detailJson));
    }

    @Override
    public Optional<WatchTarget> findWatchTarget(UUID lessonId, UUID activityId) {
        return jdbc.query("""
            SELECT a.lesson_id,
                   a.id AS activity_id,
                   a.activity_type,
                   COALESCE(
                       NULLIF((SELECT count(*)::int FROM lesson_activity_sentence m WHERE m.activity_id = a.id), 0),
                       (SELECT count(*)::int FROM lesson_sentence s WHERE s.lesson_id = a.lesson_id)
                   ) AS total_items
              FROM lesson_activity a
             WHERE a.lesson_id = :lessonId
               AND a.id = :activityId
               AND a.enabled = TRUE
               AND EXISTS(SELECT 1 FROM lesson_availability v WHERE v.lesson_id = a.lesson_id AND v.available)
            """, Map.of("lessonId", lessonId, "activityId", activityId),
            (rs, rowNum) -> new WatchTarget(
                rs.getObject("lesson_id", UUID.class),
                rs.getObject("activity_id", UUID.class),
                rs.getString("activity_type"),
                rs.getInt("total_items")
            ))
            .stream()
            .findFirst();
    }

    @Override
    public WatchCompletion completeWatchActivity(UUID learnerId, UUID lessonId, UUID activityId, int totalItems) {
        upsertLessonInProgress(learnerId, lessonId);

        Optional<String> previousStatus = jdbc.query("""
            SELECT status
              FROM lesson_activity_progress
             WHERE learner_id = :learnerId
               AND activity_id = :activityId
             FOR UPDATE
            """, Map.of("learnerId", learnerId, "activityId", activityId),
            (rs, rowNum) -> rs.getString("status"))
            .stream()
            .findFirst();

        boolean newlyCompleted = previousStatus.map(s -> !"COMPLETED".equals(s)).orElse(true);

        var updated = jdbc.queryForObject("""
            INSERT INTO lesson_activity_progress(
              learner_id,
              activity_id,
              status,
              completed_items,
              total_items,
              best_score,
              started_at,
              completed_at,
              last_practiced_at
            ) VALUES (
              :learnerId,
              :activityId,
              'COMPLETED',
              :totalItems,
              :totalItems,
              100,
              now(),
              now(),
              now()
            )
            ON CONFLICT(learner_id, activity_id) DO UPDATE SET
              status = 'COMPLETED',
              completed_items = GREATEST(lesson_activity_progress.completed_items, excluded.completed_items),
              total_items = GREATEST(lesson_activity_progress.total_items, excluded.total_items),
              best_score = GREATEST(COALESCE(lesson_activity_progress.best_score, 0), 100),
              completed_at = COALESCE(lesson_activity_progress.completed_at, now()),
              last_practiced_at = now()
            RETURNING completed_items, total_items
            """, new MapSqlParameterSource()
            .addValue("learnerId", learnerId)
            .addValue("activityId", activityId)
            .addValue("totalItems", Math.max(0, totalItems)),
            (rs, rowNum) -> new WatchCompletion(
                rs.getInt("completed_items"),
                rs.getInt("total_items"),
                newlyCompleted
            ));
        return updated;
    }

    @Override
    public ActivityProgress refreshActivityProgress(
        UUID learnerId,
        UUID lessonId,
        UUID activityId,
        int completionScore
    ) {
        upsertLessonInProgress(learnerId, lessonId);

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
                   AND (NOT EXISTS(SELECT 1 FROM lesson_authoring x WHERE x.lesson_id=s.lesson_id)
                        OR EXISTS(SELECT 1 FROM lesson_authoring x JOIN lesson_activity_sentence m
                            ON m.activity_id=:activityId AND m.sentence_id=s.id
                            WHERE x.lesson_id=s.lesson_id AND x.published_at IS NOT NULL))
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

    private void upsertLessonInProgress(UUID learnerId, UUID lessonId) {
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
               AND (
                    (
                        EXISTS (
                            SELECT 1
                              FROM lesson_activity a
                             WHERE a.lesson_id = :lessonId
                               AND a.enabled = TRUE
                               AND a.activity_type <> 'WATCH'
                        )
                        AND NOT EXISTS (
                            SELECT 1
                              FROM lesson_activity a
                              LEFT JOIN lesson_activity_progress ap
                                ON ap.activity_id = a.id
                               AND ap.learner_id = :learnerId
                             WHERE a.lesson_id = :lessonId
                               AND a.enabled = TRUE
                               AND a.activity_type <> 'WATCH'
                               AND (ap.status IS NULL OR ap.status <> 'COMPLETED')
                        )
                    )
                    OR (
                        NOT EXISTS (
                            SELECT 1
                              FROM lesson_activity a
                             WHERE a.lesson_id = :lessonId
                               AND a.enabled = TRUE
                               AND a.activity_type <> 'WATCH'
                        )
                        AND NOT EXISTS (
                            SELECT 1
                              FROM lesson_activity a
                              LEFT JOIN lesson_activity_progress ap
                                ON ap.activity_id = a.id
                               AND ap.learner_id = :learnerId
                             WHERE a.lesson_id = :lessonId
                               AND a.enabled = TRUE
                               AND a.activity_type = 'WATCH'
                               AND (ap.status IS NULL OR ap.status <> 'COMPLETED')
                        )
                    )
               )
            """, Map.of(
                "learnerId", learnerId,
                "lessonId", lessonId
            )) == 1;
    }
}
