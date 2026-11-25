package com.lyreo.lesson.infrastructure.persistence;

import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.lesson.application.port.LearnerLessonQuery;
import com.lyreo.lesson.domain.authoring.AuthoringCloze;
import com.lyreo.lesson.domain.authoring.AuthoringSentence;
import com.lyreo.lesson.domain.authoring.AuthoringText;
import com.lyreo.lesson.domain.practice.DictationMaskingPolicy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** JDBC query adapter for learner-facing Lesson catalog, details, activities, audio, and attempts. */
public final class JdbcLearnerLessonQuery implements LearnerLessonQuery {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcLearnerLessonQuery(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public CatalogPage listPublishedCatalog(
        UUID learnerId,
        UUID categoryId,
        String cefr,
        String sourceType,
        String activity,
        String query,
        int page,
        int size
    ) {
        String escapedQuery = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        var args = new MapSqlParameterSource()
            .addValue("learnerId", learnerId)
            .addValue("query", escapedQuery)
            .addValue("limit", size)
            .addValue("offset", (long) page * size);

        StringBuilder from = new StringBuilder("""
            FROM lesson l
            JOIN lesson_availability v ON v.lesson_id = l.id AND v.available = TRUE
            JOIN lesson_metadata m ON m.lesson_id = l.id AND m.deleted_at IS NULL
            LEFT JOIN lesson_authoring a ON a.lesson_id = l.id
            LEFT JOIN lesson_youtube_source s ON s.video_id = a.video_id
            LEFT JOIN lesson_youtube_channel ch ON ch.channel_id = s.channel_id
            LEFT JOIN lesson_progress lp ON lp.lesson_id = l.id AND lp.learner_id = :learnerId
            WHERE m.title ILIKE :query
            """);

        if (categoryId != null) {
            from.append(" AND EXISTS (SELECT 1 FROM lesson_category_membership cm WHERE cm.lesson_id = l.id AND cm.category_id = :categoryId)");
            args.addValue("categoryId", categoryId);
        }
        if (cefr != null && !cefr.isEmpty()) {
            from.append(" AND COALESCE(a.content_profile->>'cefr', 'UNKNOWN') = :cefr");
            args.addValue("cefr", cefr);
        }
        if (sourceType != null && !sourceType.isEmpty()) {
            from.append(" AND l.source_type = :sourceType");
            args.addValue("sourceType", sourceType);
        }
        if (activity != null && !activity.isEmpty()) {
            from.append(" AND EXISTS (SELECT 1 FROM lesson_activity la WHERE la.lesson_id = l.id AND la.enabled = TRUE AND la.activity_type = :activity)");
            args.addValue("activity", activity);
        }

        long total = jdbc.queryForObject("SELECT count(*) " + from, args, Long.class);
        if (total == 0) {
            return new CatalogPage(List.of(), 0, page, size);
        }

        List<CatalogRow> rows = jdbc.query("""
            SELECT l.id,
                   m.title,
                   m.description,
                   COALESCE(m.image_url, s.thumbnail_url) AS image_url,
                   l.source_type,
                   COALESCE(a.content_profile->>'cefr', 'UNKNOWN') AS cefr,
                   COALESCE(a.content_profile->>'contentType', 'UNKNOWN') AS content_type,
                   COALESCE(a.revision, 0) AS revision,
                   CASE
                       WHEN a.source_start_ms IS NOT NULL AND a.source_end_ms IS NOT NULL
                           THEN a.source_end_ms - a.source_start_ms
                       ELSE NULL
                   END AS duration_ms,
                   (l.canonical_audio_object_key IS NOT NULL) AS audio_ready,
                   s.channel_id,
                   COALESCE(ch.title, s.channel_title) AS channel_title,
                   GREATEST(m.updated_at, COALESCE(l.updated_at, m.updated_at)) AS updated_at,
                   COALESCE(lp.status, 'NOT_STARTED') AS progress_status,
                   lp.started_at,
                   lp.completed_at,
                   lp.last_activity_at,
                   (SELECT count(*)::int FROM lesson_sentence ls WHERE ls.lesson_id = l.id) AS sentence_count,
                   (SELECT count(*)::int
                      FROM lesson_activity la
                     WHERE la.lesson_id = l.id
                       AND la.enabled = TRUE
                       AND la.activity_type <> 'WATCH') AS total_practice_activities,
                   (SELECT count(*)::int
                      FROM lesson_activity la
                      JOIN lesson_activity_progress lap
                        ON lap.activity_id = la.id AND lap.learner_id = :learnerId
                     WHERE la.lesson_id = l.id
                       AND la.enabled = TRUE
                       AND la.activity_type <> 'WATCH'
                       AND lap.status = 'COMPLETED') AS completed_activities,
                   COALESCE(
                       (SELECT jsonb_agg(la.activity_type ORDER BY la.position)::text
                          FROM lesson_activity la
                         WHERE la.lesson_id = l.id AND la.enabled = TRUE),
                       '[]'
                   ) AS activities_json
            """ + from + " ORDER BY GREATEST(m.updated_at, COALESCE(l.updated_at, m.updated_at)) DESC, l.id LIMIT :limit OFFSET :offset",
            args,
            (rs, rowNum) -> new CatalogRow(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("image_url"),
                rs.getString("source_type"),
                rs.getString("cefr"),
                rs.getString("content_type"),
                rs.getLong("revision"),
                rs.getObject("duration_ms", Long.class),
                rs.getBoolean("audio_ready"),
                rs.getString("channel_id"),
                rs.getString("channel_title"),
                toInstant(rs.getTimestamp("updated_at")),
                new ProgressSummary(
                    rs.getString("progress_status"),
                    toInstant(rs.getTimestamp("started_at")),
                    toInstant(rs.getTimestamp("completed_at")),
                    toInstant(rs.getTimestamp("last_activity_at")),
                    rs.getInt("completed_activities"),
                    rs.getInt("total_practice_activities")
                ),
                rs.getInt("sentence_count"),
                readStringList(rs.getString("activities_json"))
            ));

        if (rows.isEmpty()) {
            return new CatalogPage(List.of(), total, page, size);
        }

        Map<UUID, List<CategorySummary>> categoriesByLesson = loadCategories(
            rows.stream().map(CatalogRow::id).toList()
        );

        List<CatalogItem> items = rows.stream()
            .map(row -> new CatalogItem(
                row.id(),
                row.title(),
                row.description(),
                row.imageUrl(),
                row.sourceType(),
                row.cefr(),
                row.contentType(),
                row.revision(),
                row.durationMs(),
                row.audioReady(),
                row.channelId(),
                row.channelTitle(),
                categoriesByLesson.getOrDefault(row.id(), List.of()),
                row.activities(),
                row.sentenceCount(),
                row.progress(),
                row.updatedAt()
            ))
            .toList();

        return new CatalogPage(items, total, page, size);
    }

    @Override
    public Optional<LessonDetail> findPublishedDetail(UUID learnerId, UUID lessonId) {
        var rows = jdbc.query("""
            SELECT l.id,
                   m.title,
                   m.description,
                   COALESCE(m.image_url, s.thumbnail_url) AS image_url,
                   l.source_type,
                   COALESCE(a.revision, 0) AS revision,
                   a.video_id,
                   a.source_start_ms,
                   a.source_end_ms,
                   (l.canonical_audio_object_key IS NOT NULL) AS audio_ready,
                   COALESCE(a.content_profile->>'cefr', 'UNKNOWN') AS cefr,
                   COALESCE(a.content_profile->>'contentType', 'UNKNOWN') AS content_type,
                   COALESCE(a.generation_options->>'accent', 'US') AS accent,
                   COALESCE(a.generation_options->>'locale', 'vi-VN') AS locale,
                   s.channel_id,
                   COALESCE(ch.title, s.channel_title) AS channel_title,
                   GREATEST(m.updated_at, COALESCE(l.updated_at, m.updated_at)) AS updated_at,
                   COALESCE(lp.status, 'NOT_STARTED') AS progress_status,
                   lp.started_at,
                   lp.completed_at,
                   lp.last_activity_at
              FROM lesson l
              JOIN lesson_availability v ON v.lesson_id = l.id AND v.available = TRUE
              JOIN lesson_metadata m ON m.lesson_id = l.id AND m.deleted_at IS NULL
              LEFT JOIN lesson_authoring a ON a.lesson_id = l.id
              LEFT JOIN lesson_youtube_source s ON s.video_id = a.video_id
              LEFT JOIN lesson_youtube_channel ch ON ch.channel_id = s.channel_id
              LEFT JOIN lesson_progress lp ON lp.lesson_id = l.id AND lp.learner_id = :learnerId
             WHERE l.id = :lessonId
            """, Map.of("learnerId", learnerId, "lessonId", lessonId), (rs, rowNum) -> new DetailHeader(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("image_url"),
                rs.getString("source_type"),
                rs.getLong("revision"),
                rs.getString("video_id"),
                rs.getObject("source_start_ms", Long.class),
                rs.getObject("source_end_ms", Long.class),
                rs.getBoolean("audio_ready"),
                rs.getString("cefr"),
                rs.getString("content_type"),
                rs.getString("accent"),
                rs.getString("locale"),
                rs.getString("channel_id"),
                rs.getString("channel_title"),
                toInstant(rs.getTimestamp("updated_at")),
                rs.getString("progress_status"),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("completed_at")),
                toInstant(rs.getTimestamp("last_activity_at"))
            ));

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        DetailHeader header = rows.getFirst();
        List<ActivityProgressItem> activities = loadActivities(learnerId, lessonId);

        int totalPractice = (int) activities.stream()
            .filter(act -> act.enabled() && !"WATCH".equals(act.activityType()))
            .count();
        int completedPractice = (int) activities.stream()
            .filter(act -> act.enabled() && !"WATCH".equals(act.activityType()) && "COMPLETED".equals(act.status()))
            .count();

        List<SentenceSummary> sentences = loadSentenceSummaries(lessonId);
        List<CategorySummary> categories = loadCategories(List.of(lessonId)).getOrDefault(lessonId, List.of());

        return Optional.of(new LessonDetail(
            header.id(),
            header.title(),
            header.description(),
            header.imageUrl(),
            header.sourceType(),
            header.revision(),
            header.videoId(),
            header.sourceStartMs(),
            header.sourceEndMs(),
            header.audioReady(),
            header.cefr(),
            header.contentType(),
            header.accent(),
            header.locale(),
            header.channelId(),
            header.channelTitle(),
            categories,
            new ProgressSummary(
                header.progressStatus(),
                header.startedAt(),
                header.completedAt(),
                header.lastActivityAt(),
                completedPractice,
                totalPractice
            ),
            activities,
            sentences,
            header.updatedAt()
        ));
    }

    @Override
    public Optional<ActivityContext> findActivityContext(UUID learnerId, UUID lessonId, UUID activityId) {
        var headers = jdbc.query("""
            SELECT l.id AS lesson_id,
                   COALESCE(a.revision, 0) AS revision,
                   a.video_id,
                   a.source_start_ms,
                   a.source_end_ms,
                   (l.canonical_audio_object_key IS NOT NULL) AS audio_ready,
                   (a.lesson_id IS NOT NULL) AS authored
              FROM lesson l
              JOIN lesson_availability v ON v.lesson_id = l.id AND v.available = TRUE
              JOIN lesson_metadata m ON m.lesson_id = l.id AND m.deleted_at IS NULL
              LEFT JOIN lesson_authoring a ON a.lesson_id = l.id
             WHERE l.id = :lessonId
            """, Map.of("lessonId", lessonId), (rs, rowNum) -> new ActivityLessonHeader(
                rs.getObject("lesson_id", UUID.class),
                rs.getLong("revision"),
                rs.getString("video_id"),
                rs.getObject("source_start_ms", Long.class),
                rs.getObject("source_end_ms", Long.class),
                rs.getBoolean("audio_ready"),
                rs.getBoolean("authored")
            ));

        if (headers.isEmpty()) {
            return Optional.empty();
        }
        ActivityLessonHeader header = headers.getFirst();

        Optional<ActivityProgressItem> activityOpt = loadActivities(learnerId, lessonId).stream()
            .filter(act -> activityId.equals(act.id()) && act.enabled())
            .findFirst();
        if (activityOpt.isEmpty()) {
            return Optional.empty();
        }
        ActivityProgressItem activity = activityOpt.get();

        List<SentenceRow> sentenceRows = jdbc.query("""
            SELECT s.id,
                   s.position,
                   s.text,
                   s.audio_start_ms,
                   s.audio_end_ms
              FROM lesson_sentence s
             WHERE s.lesson_id = :lessonId
               AND (
                    (:authored = FALSE AND NOT EXISTS (
                        SELECT 1 FROM lesson_activity_sentence m WHERE m.activity_id = :activityId
                    ))
                    OR EXISTS (
                        SELECT 1 FROM lesson_activity_sentence m
                         WHERE m.activity_id = :activityId AND m.sentence_id = s.id
                    )
               )
             ORDER BY s.position
            """, new MapSqlParameterSource()
            .addValue("lessonId", lessonId)
            .addValue("activityId", activityId)
            .addValue("authored", header.authored()),
            (rs, rowNum) -> new SentenceRow(
                rs.getObject("id", UUID.class),
                rs.getInt("position"),
                rs.getString("text"),
                rs.getObject("audio_start_ms", Long.class),
                rs.getObject("audio_end_ms", Long.class)
            ));

        if (sentenceRows.isEmpty()) {
            return Optional.of(new ActivityContext(
                header.lessonId(),
                header.revision(),
                header.videoId(),
                header.sourceStartMs(),
                header.sourceEndMs(),
                header.audioReady(),
                activity,
                List.of()
            ));
        }

        List<UUID> sentenceIds = sentenceRows.stream().map(SentenceRow::id).toList();
        Map<UUID, List<DictationMaskingPolicy.WordView>> wordsBySentence = loadWordTimestamps(sentenceIds);
        Map<UUID, PronunciationRow> ipaBySentence = loadPronunciations(sentenceIds);
        Map<UUID, AuthoringSentence> contentBySentence = new HashMap<>();
        Map<UUID, String> translationBySentence = new HashMap<>();
        Map<UUID, List<DictationMaskingPolicy.EntitySpan>> entitySpansBySentence = new HashMap<>();
        Map<UUID, List<AuthoringSentence.Group>> thoughtGroupsBySentence = new HashMap<>();
        loadAnnotations(lessonId, sentenceIds, contentBySentence, translationBySentence,
            entitySpansBySentence, thoughtGroupsBySentence);

        Map<UUID, LatestSentenceAttempt> latestAttempts = loadLatestAttempts(learnerId, activityId);
        Map<UUID, Integer> bestScores = loadBestScores(learnerId, activityId);

        List<RawActivitySentence> sentences = new ArrayList<>(sentenceRows.size());
        for (SentenceRow row : sentenceRows) {
            AuthoringSentence authored = contentBySentence.get(row.id());
            List<DictationMaskingPolicy.WordView> words = wordsBySentence.getOrDefault(row.id(), List.of());
            if (authored != null && authored.words != null && !authored.words.isEmpty()) {
                words = toWordViews(authored.words);
            }
            AuthoringCloze.Targets cloze = authored != null ? authored.cloze : null;
            if (cloze == null || cloze.text() == null || !cloze.text().equals(row.text())) {
                AuthoringSentence temp = authored != null
                    ? authored
                    : new AuthoringSentence(row.id(), row.text(), "LEARN");
                cloze = AuthoringCloze.generate(temp);
            }
            PronunciationRow pron = ipaBySentence.get(row.id());
            String ipa = authored != null && authored.ipa != null ? authored.ipa : (pron != null ? pron.ipa() : null);
            String ipaAccent = authored != null && authored.ipaAccent != null
                ? authored.ipaAccent
                : (pron != null ? pron.accent() : null);
            String ipaVersion = authored != null && authored.ipaVersion != null
                ? authored.ipaVersion
                : (pron != null ? pron.model() : null);
            String translation = authored != null && authored.translation != null
                ? authored.translation
                : translationBySentence.get(row.id());
            List<AuthoringSentence.Group> thoughtGroups = authored != null && authored.thoughtGroups != null
                && !authored.thoughtGroups.isEmpty()
                ? authored.thoughtGroups
                : thoughtGroupsBySentence.getOrDefault(row.id(), List.of());

            sentences.add(new RawActivitySentence(
                row.id(),
                row.position(),
                row.text(),
                row.audioStartMs(),
                row.audioEndMs(),
                cloze,
                words,
                authored != null && authored.ipaReviewIssues != null ? authored.ipaReviewIssues : List.of(),
                authored != null && authored.hints != null ? authored.hints : List.of(),
                entitySpansBySentence.getOrDefault(row.id(), List.of()),
                translation,
                ipa,
                ipaAccent,
                ipaVersion,
                authored != null && authored.vocabulary != null ? authored.vocabulary : List.of(),
                authored != null && authored.grammar != null ? authored.grammar : List.of(),
                authored != null && authored.notes != null ? authored.notes : List.of(),
                thoughtGroups,
                latestAttempts.get(row.id()),
                bestScores.get(row.id())
            ));
        }

        return Optional.of(new ActivityContext(
            header.lessonId(),
            header.revision(),
            header.videoId(),
            header.sourceStartMs(),
            header.sourceEndMs(),
            header.audioReady(),
            activity,
            List.copyOf(sentences)
        ));
    }

    @Override
    public boolean readProperNounHintsPreference(UUID learnerId) {
        var rows = jdbc.query("""
            SELECT (preferences_json->>'properNounHints')::boolean AS proper_noun_hints
              FROM learner_profile
             WHERE learner_id = :learnerId
            """, Map.of("learnerId", learnerId), (rs, rowNum) -> rs.getObject("proper_noun_hints", Boolean.class));
        if (rows.isEmpty() || rows.getFirst() == null) {
            return true;
        }
        return rows.getFirst();
    }

    @Override
    public Optional<AudioAsset> findAudioForLearner(UUID learnerId, UUID lessonId) {
        var rows = jdbc.query("""
            SELECT l.canonical_audio_object_key
              FROM lesson l
              JOIN lesson_metadata m ON m.lesson_id = l.id
              LEFT JOIN lesson_availability v ON v.lesson_id = l.id
             WHERE l.id = :lessonId
               AND (
                    COALESCE(v.available, FALSE) = TRUE
                    OR EXISTS (
                        SELECT 1 FROM lesson_progress lp
                         WHERE lp.lesson_id = l.id AND lp.learner_id = :learnerId
                    )
                    OR EXISTS (
                        SELECT 1 FROM lesson_practice_attempt pa
                         WHERE pa.lesson_id = l.id AND pa.learner_id = :learnerId
                    )
               )
            """, Map.of("learnerId", learnerId, "lessonId", lessonId),
            (rs, rowNum) -> rs.getString("canonical_audio_object_key"));

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        String key = rows.getFirst();
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new AudioAsset(key, contentTypeForKey(key)));
    }

    @Override
    public AttemptPage listAttempts(
        UUID learnerId,
        UUID lessonId,
        UUID activityId,
        String activityType,
        UUID sentenceId,
        int page,
        int size
    ) {
        boolean visibleOrAttempted = Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                  FROM lesson l
                  LEFT JOIN lesson_availability v ON v.lesson_id = l.id
                 WHERE l.id = :lessonId
                   AND (
                        COALESCE(v.available, FALSE) = TRUE
                        OR EXISTS (
                            SELECT 1 FROM lesson_progress lp
                             WHERE lp.lesson_id = l.id AND lp.learner_id = :learnerId
                        )
                        OR EXISTS (
                            SELECT 1 FROM lesson_practice_attempt pa
                             WHERE pa.lesson_id = l.id AND pa.learner_id = :learnerId
                        )
                   )
            )
            """, Map.of("lessonId", lessonId, "learnerId", learnerId), Boolean.class));
        if (!visibleOrAttempted) {
            throw new ResourceNotFoundException("Lesson not found");
        }

        var args = new MapSqlParameterSource()
            .addValue("learnerId", learnerId)
            .addValue("lessonId", lessonId)
            .addValue("limit", size)
            .addValue("offset", (long) page * size);

        StringBuilder where = new StringBuilder(" WHERE a.learner_id = :learnerId AND a.lesson_id = :lessonId ");
        if (activityId != null) {
            where.append(" AND a.activity_id = :activityId ");
            args.addValue("activityId", activityId);
        }
        if (activityType != null && !activityType.isEmpty()) {
            where.append(" AND a.activity_type = :activityType ");
            args.addValue("activityType", activityType);
        }
        if (sentenceId != null) {
            where.append(" AND a.sentence_id = :sentenceId ");
            args.addValue("sentenceId", sentenceId);
        }

        long total = jdbc.queryForObject(
            "SELECT count(*) FROM lesson_practice_attempt a" + where,
            args,
            Long.class
        );
        if (total == 0) {
            return new AttemptPage(List.of(), 0, page, size);
        }

        List<AttemptItem> items = jdbc.query("""
            SELECT a.id,
                   a.lesson_id,
                   a.activity_id,
                   a.sentence_id,
                   a.activity_type,
                   a.server_score,
                   a.answer_text,
                   s.text AS expected_text,
                   a.detail_json::text AS detail_json,
                   a.created_at
              FROM lesson_practice_attempt a
              LEFT JOIN lesson_sentence s ON s.id = a.sentence_id
            """ + where + " ORDER BY a.created_at DESC, a.id DESC LIMIT :limit OFFSET :offset",
            args,
            (rs, rowNum) -> new AttemptItem(
                rs.getObject("id", UUID.class),
                rs.getObject("lesson_id", UUID.class),
                rs.getObject("activity_id", UUID.class),
                rs.getObject("sentence_id", UUID.class),
                rs.getString("activity_type"),
                rs.getInt("server_score"),
                rs.getString("answer_text"),
                rs.getString("expected_text"),
                decodeObject(rs.getString("detail_json")),
                toInstant(rs.getTimestamp("created_at"))
            ));

        return new AttemptPage(items, total, page, size);
    }

    private List<ActivityProgressItem> loadActivities(UUID learnerId, UUID lessonId) {
        return jdbc.query("""
            SELECT a.id,
                   a.activity_type,
                   a.position,
                   a.enabled,
                   COALESCE(ap.status, 'NOT_STARTED') AS status,
                   COALESCE(ap.completed_items, 0) AS completed_items,
                   COALESCE(
                       ap.total_items,
                       NULLIF((SELECT count(*)::int FROM lesson_activity_sentence m WHERE m.activity_id = a.id), 0),
                       (SELECT count(*)::int FROM lesson_sentence s WHERE s.lesson_id = a.lesson_id)
                   ) AS total_items,
                   ap.best_score,
                   ap.started_at,
                   ap.completed_at,
                   ap.last_practiced_at
              FROM lesson_activity a
              LEFT JOIN lesson_activity_progress ap
                ON ap.activity_id = a.id AND ap.learner_id = :learnerId
             WHERE a.lesson_id = :lessonId
             ORDER BY a.position, a.id
            """, Map.of("learnerId", learnerId, "lessonId", lessonId), (rs, rowNum) -> new ActivityProgressItem(
                rs.getObject("id", UUID.class),
                rs.getString("activity_type"),
                rs.getInt("position"),
                rs.getBoolean("enabled"),
                rs.getString("status"),
                rs.getInt("completed_items"),
                rs.getInt("total_items"),
                rs.getObject("best_score", Integer.class),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("completed_at")),
                toInstant(rs.getTimestamp("last_practiced_at"))
            ));
    }

    private List<SentenceSummary> loadSentenceSummaries(UUID lessonId) {
        return jdbc.query("""
            SELECT s.id,
                   s.position,
                   s.audio_start_ms,
                   s.audio_end_ms,
                   s.text,
                   (SELECT count(*)::int FROM lesson_word_timestamp w WHERE w.sentence_id = s.id) AS word_ts_count,
                   EXISTS (
                       SELECT 1 FROM lesson_activity a
                       JOIN lesson_activity_sentence m ON m.activity_id = a.id
                        WHERE a.lesson_id = s.lesson_id
                          AND a.activity_type = 'DICTATION'
                          AND a.enabled = TRUE
                          AND m.sentence_id = s.id
                   ) AS dictation_selected,
                   EXISTS (
                       SELECT 1 FROM lesson_activity a
                       JOIN lesson_activity_sentence m ON m.activity_id = a.id
                        WHERE a.lesson_id = s.lesson_id
                          AND a.activity_type = 'SHADOWING'
                          AND a.enabled = TRUE
                          AND m.sentence_id = s.id
                   ) AS shadowing_selected
              FROM lesson_sentence s
             WHERE s.lesson_id = :lessonId
             ORDER BY s.position
            """, Map.of("lessonId", lessonId), (rs, rowNum) -> {
                int wordCount = rs.getInt("word_ts_count");
                if (wordCount == 0) {
                    wordCount = AuthoringText.tokens(rs.getString("text")).size();
                }
                return new SentenceSummary(
                    rs.getObject("id", UUID.class),
                    rs.getInt("position"),
                    rs.getObject("audio_start_ms", Long.class),
                    rs.getObject("audio_end_ms", Long.class),
                    wordCount,
                    rs.getBoolean("dictation_selected"),
                    rs.getBoolean("shadowing_selected")
                );
            });
    }

    private Map<UUID, List<CategorySummary>> loadCategories(List<UUID> lessonIds) {
        if (lessonIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<CategorySummary>> result = new LinkedHashMap<>();
        jdbc.query("""
            SELECT cm.lesson_id,
                   c.id,
                   c.name,
                   c.translations::text AS translations
              FROM lesson_category_membership cm
              JOIN lesson_category c ON c.id = cm.category_id
             WHERE cm.lesson_id IN (:ids)
             ORDER BY lower(c.name)
            """, new MapSqlParameterSource("ids", lessonIds), rs -> {
                UUID lessonId = rs.getObject("lesson_id", UUID.class);
                result.computeIfAbsent(lessonId, ignored -> new ArrayList<>())
                    .add(new CategorySummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        readStringMap(rs.getString("translations"))
                    ));
            });
        return result;
    }

    private Map<UUID, List<DictationMaskingPolicy.WordView>> loadWordTimestamps(List<UUID> sentenceIds) {
        Map<UUID, List<DictationMaskingPolicy.WordView>> result = new HashMap<>();
        jdbc.query("""
            SELECT sentence_id, position, surface_text, start_ms, end_ms
              FROM lesson_word_timestamp
             WHERE sentence_id IN (:ids)
             ORDER BY sentence_id, position
            """, new MapSqlParameterSource("ids", sentenceIds), rs -> {
                UUID sentenceId = rs.getObject("sentence_id", UUID.class);
                result.computeIfAbsent(sentenceId, ignored -> new ArrayList<>())
                    .add(new DictationMaskingPolicy.WordView(
                        rs.getInt("position"),
                        rs.getString("surface_text"),
                        rs.getLong("start_ms"),
                        rs.getLong("end_ms"),
                        -1,
                        -1,
                        -1,
                        -1,
                        false
                    ));
            });
        return result;
    }

    private Map<UUID, PronunciationRow> loadPronunciations(List<UUID> sentenceIds) {
        Map<UUID, PronunciationRow> result = new HashMap<>();
        jdbc.query("""
            SELECT DISTINCT ON (sentence_id) sentence_id, ipa, accent, model
              FROM sentence_pronunciation
             WHERE sentence_id IN (:ids)
             ORDER BY sentence_id
            """, new MapSqlParameterSource("ids", sentenceIds), rs -> {
                result.put(
                    rs.getObject("sentence_id", UUID.class),
                    new PronunciationRow(
                        rs.getString("ipa"),
                        rs.getString("accent"),
                        rs.getString("model")
                    )
                );
            });
        return result;
    }

    private void loadAnnotations(
        UUID lessonId,
        List<UUID> sentenceIds,
        Map<UUID, AuthoringSentence> contentBySentence,
        Map<UUID, String> translationBySentence,
        Map<UUID, List<DictationMaskingPolicy.EntitySpan>> entitySpansBySentence,
        Map<UUID, List<AuthoringSentence.Group>> thoughtGroupsBySentence
    ) {
        jdbc.query("""
            SELECT sentence_id, annotation_type, payload_json::text AS payload_json
              FROM lesson_annotation
             WHERE lesson_id = :lessonId
               AND sentence_id IN (:ids)
             ORDER BY created_at, annotation_type
            """, new MapSqlParameterSource()
            .addValue("lessonId", lessonId)
            .addValue("ids", sentenceIds), rs -> {
                UUID sentenceId = rs.getObject("sentence_id", UUID.class);
                String type = rs.getString("annotation_type");
                String json = rs.getString("payload_json");
                if (sentenceId == null || json == null) {
                    return;
                }
                switch (type) {
                    case "CONTENT" -> contentBySentence.put(
                        sentenceId,
                        mapper.readValue(json, AuthoringSentence.class)
                    );
                    case "TRANSLATION" -> {
                        JsonNode node = mapper.readTree(json);
                        String text = node.isTextual()
                            ? node.asText()
                            : firstText(node, "translation", "text");
                        if (!text.isBlank()) {
                            translationBySentence.put(sentenceId, text);
                        }
                    }
                    case "ENTITY_HINTS", "DICTATION_HINTS" -> {
                        JsonNode node = mapper.readTree(json);
                        if (node.isArray()) {
                            var spans = entitySpansBySentence.computeIfAbsent(sentenceId, ignored -> new ArrayList<>());
                            for (JsonNode item : node) {
                                int start = item.path("start_char").asInt(item.path("start").asInt(-1));
                                int end = item.path("end_char").asInt(item.path("end").asInt(-1));
                                String surface = firstText(item, "text", "surface");
                                spans.add(new DictationMaskingPolicy.EntitySpan(start, end, surface));
                            }
                        }
                    }
                    case "THOUGHT_GROUPS" -> {
                        JsonNode node = mapper.readTree(json);
                        JsonNode groups = node.isArray() ? node : node.path("groups");
                        if (groups.isArray()) {
                            var list = thoughtGroupsBySentence.computeIfAbsent(sentenceId, ignored -> new ArrayList<>());
                            for (JsonNode g : groups) {
                                int start = g.path("tokenStart").asInt(g.path("start").asInt(-1));
                                int end = g.path("tokenEnd").asInt(g.path("end").asInt(-1));
                                if (start >= 0 && end > start) {
                                    list.add(new AuthoringSentence.Group(start, end));
                                }
                            }
                        }
                    }
                    default -> {
                        // Other annotation types (LEXICAL, GRAMMAR, LEARNING_TIPS) are loaded via CONTENT when published.
                    }
                }
            });
    }

    private Map<UUID, LatestSentenceAttempt> loadLatestAttempts(UUID learnerId, UUID activityId) {
        Map<UUID, LatestSentenceAttempt> result = new HashMap<>();
        jdbc.query("""
            SELECT DISTINCT ON (sentence_id)
                   sentence_id,
                   id AS attempt_id,
                   server_score,
                   answer_text,
                   created_at
              FROM lesson_practice_attempt
             WHERE learner_id = :learnerId
               AND activity_id = :activityId
               AND sentence_id IS NOT NULL
             ORDER BY sentence_id, created_at DESC, id DESC
            """, Map.of("learnerId", learnerId, "activityId", activityId), rs -> {
                result.put(
                    rs.getObject("sentence_id", UUID.class),
                    new LatestSentenceAttempt(
                        rs.getObject("attempt_id", UUID.class),
                        rs.getInt("server_score"),
                        rs.getString("answer_text"),
                        toInstant(rs.getTimestamp("created_at"))
                    )
                );
            });
        return result;
    }

    private Map<UUID, Integer> loadBestScores(UUID learnerId, UUID activityId) {
        Map<UUID, Integer> result = new HashMap<>();
        jdbc.query("""
            SELECT sentence_id, MAX(server_score)::int AS best_score
              FROM lesson_practice_attempt
             WHERE learner_id = :learnerId
               AND activity_id = :activityId
               AND sentence_id IS NOT NULL
             GROUP BY sentence_id
            """, Map.of("learnerId", learnerId, "activityId", activityId), rs -> {
                result.put(
                    rs.getObject("sentence_id", UUID.class),
                    rs.getObject("best_score", Integer.class)
                );
            });
        return result;
    }

    private static List<DictationMaskingPolicy.WordView> toWordViews(List<AuthoringSentence.Word> words) {
        List<DictationMaskingPolicy.WordView> views = new ArrayList<>(words.size());
        for (int i = 0; i < words.size(); i++) {
            var w = words.get(i);
            views.add(new DictationMaskingPolicy.WordView(
                i,
                w.text(),
                w.startMs(),
                w.endMs(),
                w.charStart(),
                w.charEnd(),
                w.tokenStart(),
                w.tokenEnd(),
                false
            ));
        }
        return List.copyOf(views);
    }

    private static String contentTypeForKey(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".webm")) return "audio/webm";
        return "audio/wav";
    }

    private static String firstText(JsonNode node, String first, String fallback) {
        String primary = node.path(first).asText("");
        return primary.isBlank() ? node.path(fallback).asText("") : primary;
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        return mapper.readValue(json, STRING_LIST);
    }

    private Map<String, String> readStringMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        return mapper.readValue(json, STRING_MAP);
    }

    private Object decodeObject(String json) {
        if (json == null || json.isBlank()) return Map.of();
        return mapper.readValue(json, Object.class);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private record CatalogRow(
        UUID id,
        String title,
        String description,
        String imageUrl,
        String sourceType,
        String cefr,
        String contentType,
        long revision,
        Long durationMs,
        boolean audioReady,
        String channelId,
        String channelTitle,
        Instant updatedAt,
        ProgressSummary progress,
        int sentenceCount,
        List<String> activities
    ) {}

    private record DetailHeader(
        UUID id,
        String title,
        String description,
        String imageUrl,
        String sourceType,
        long revision,
        String videoId,
        Long sourceStartMs,
        Long sourceEndMs,
        boolean audioReady,
        String cefr,
        String contentType,
        String accent,
        String locale,
        String channelId,
        String channelTitle,
        Instant updatedAt,
        String progressStatus,
        Instant startedAt,
        Instant completedAt,
        Instant lastActivityAt
    ) {}

    private record ActivityLessonHeader(
        UUID lessonId,
        long revision,
        String videoId,
        Long sourceStartMs,
        Long sourceEndMs,
        boolean audioReady,
        boolean authored
    ) {}

    private record SentenceRow(
        UUID id,
        int position,
        String text,
        Long audioStartMs,
        Long audioEndMs
    ) {}

    private record PronunciationRow(String ipa, String accent, String model) {}
}
