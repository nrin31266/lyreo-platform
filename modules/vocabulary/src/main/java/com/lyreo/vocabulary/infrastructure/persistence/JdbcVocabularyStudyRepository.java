package com.lyreo.vocabulary.infrastructure.persistence;

import com.lyreo.vocabulary.application.port.VocabularyCatalog;
import com.lyreo.vocabulary.application.port.VocabularyStudyRepository;
import com.lyreo.vocabulary.domain.PracticeMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL implementation of {@link VocabularyStudyRepository}. */
public class JdbcVocabularyStudyRepository implements VocabularyStudyRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcVocabularyStudyRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<UUID> findActiveReleaseId() {
        List<UUID> rows = jdbc.query("""
            SELECT COALESCE(
                (SELECT release_id FROM dataset_active_release WHERE domain = 'vocabulary'),
                (SELECT id FROM dataset_release WHERE domain = 'vocabulary' ORDER BY created_at DESC LIMIT 1)
            ) AS release_id
            """, Map.of(), (rs, n) -> rs.getObject("release_id", UUID.class));
        if (rows.isEmpty() || rows.getFirst() == null) {
            return Optional.empty();
        }
        return Optional.of(rows.getFirst());
    }

    @Override
    public List<PackRow> findVisiblePacks(UUID releaseId, UUID learnerId) {
        return jdbc.query("""
            SELECT p.id, p.title_en, p.title_vi,
                   count(DISTINCT u.id) AS unit_count,
                   count(DISTINCT e.id) AS entry_count,
                   count(DISTINCT CASE WHEN ep.status = 'COMPLETED' THEN e.id END) AS completed_entries,
                   p.access_mode, p.required_feature_key,
                   (SELECT e2.image_id FROM vocabulary_unit_entry e2
                    JOIN vocabulary_unit u2 ON u2.id = e2.unit_id
                    WHERE u2.pack_id = p.id AND NOT u2.hidden AND NOT e2.hidden AND e2.image_id IS NOT NULL
                    ORDER BY u2.position, e2.position LIMIT 1) AS cover_image_id
            FROM vocabulary_pack p
            LEFT JOIN vocabulary_unit u ON u.pack_id = p.id AND NOT u.hidden
            LEFT JOIN vocabulary_unit_entry e ON e.unit_id = u.id AND NOT e.hidden
            LEFT JOIN vocabulary_entry_progress ep ON ep.entry_id = e.id AND ep.learner_id = :learnerId
            WHERE p.release_id = :releaseId AND NOT p.hidden
            GROUP BY p.id
            ORDER BY p.position
            """, Map.of("releaseId", releaseId, "learnerId", learnerId), (rs, n) -> new PackRow(
            rs.getObject("id", UUID.class),
            rs.getString("title_en"),
            rs.getString("title_vi"),
            rs.getLong("unit_count"),
            rs.getLong("entry_count"),
            rs.getLong("completed_entries"),
            rs.getString("access_mode"),
            rs.getString("required_feature_key"),
            rs.getObject("cover_image_id", UUID.class)
        ));
    }

    @Override
    public Optional<PackRow> findVisiblePack(UUID packId) {
        return jdbc.query("""
            SELECT p.id, p.title_en, p.title_vi,
                   0::bigint AS unit_count, 0::bigint AS entry_count, 0::bigint AS completed_entries,
                   p.access_mode, p.required_feature_key, NULL::uuid AS cover_image_id
            FROM vocabulary_pack p
            WHERE p.id = :packId AND NOT p.hidden
            """, Map.of("packId", packId), (rs, n) -> new PackRow(
            rs.getObject("id", UUID.class),
            rs.getString("title_en"),
            rs.getString("title_vi"),
            rs.getLong("unit_count"),
            rs.getLong("entry_count"),
            rs.getLong("completed_entries"),
            rs.getString("access_mode"),
            rs.getString("required_feature_key"),
            rs.getObject("cover_image_id", UUID.class)
        )).stream().findFirst();
    }

    @Override
    public List<UnitRow> findVisibleUnits(UUID packId, UUID learnerId) {
        return jdbc.query("""
            SELECT u.id, u.pack_id, u.position, u.level, u.title,
                   count(DISTINCT e.id) AS entry_count,
                   count(DISTINCT CASE WHEN ep.status = 'COMPLETED' THEN e.id END) AS completed_entries,
                   u.revision
            FROM vocabulary_unit u
            LEFT JOIN vocabulary_unit_entry e ON e.unit_id = u.id AND NOT e.hidden
            LEFT JOIN vocabulary_entry_progress ep ON ep.entry_id = e.id AND ep.learner_id = :learnerId
            WHERE u.pack_id = :packId AND NOT u.hidden
            GROUP BY u.id
            ORDER BY u.position
            """, Map.of("packId", packId, "learnerId", learnerId), (rs, n) -> new UnitRow(
            rs.getObject("id", UUID.class),
            rs.getObject("pack_id", UUID.class),
            rs.getInt("position"),
            rs.getString("level"),
            rs.getString("title"),
            rs.getLong("entry_count"),
            rs.getLong("completed_entries"),
            rs.getLong("revision")
        ));
    }

    @Override
    public Optional<UnitWithPackRow> findVisibleUnit(UUID unitId) {
        return jdbc.query("""
            SELECT u.id, u.pack_id, u.position, u.level, u.title, u.revision,
                   p.access_mode, p.required_feature_key
            FROM vocabulary_unit u
            JOIN vocabulary_pack p ON p.id = u.pack_id
            WHERE u.id = :unitId AND NOT u.hidden AND NOT p.hidden
            """, Map.of("unitId", unitId), (rs, n) -> new UnitWithPackRow(
            rs.getObject("id", UUID.class),
            rs.getObject("pack_id", UUID.class),
            rs.getInt("position"),
            rs.getString("level"),
            rs.getString("title"),
            rs.getLong("revision"),
            rs.getString("access_mode"),
            rs.getString("required_feature_key")
        )).stream().findFirst();
    }

    @Override
    public List<EntryWithProgressRow> findVisibleEntries(UUID unitId, UUID learnerId) {
        return jdbc.query("""
            SELECT e.id, e.unit_id, e.position, e.term, e.revision, e.content::text AS content_json,
                   e.image_id, e.uk_audio_id, e.us_audio_id, e.example_audio_id, e.second_example_audio_id,
                   COALESCE(ep.status, 'NOT_STARTED') AS progress_status
            FROM vocabulary_unit_entry e
            JOIN vocabulary_unit u ON u.id = e.unit_id
            JOIN vocabulary_pack p ON p.id = u.pack_id
            LEFT JOIN vocabulary_entry_progress ep ON ep.entry_id = e.id AND ep.learner_id = :learnerId
            WHERE e.unit_id = :unitId AND NOT e.hidden AND NOT u.hidden AND NOT p.hidden
            ORDER BY e.position
            """, Map.of("unitId", unitId, "learnerId", learnerId), (rs, n) -> new EntryWithProgressRow(
            rs.getObject("id", UUID.class),
            rs.getObject("unit_id", UUID.class),
            rs.getInt("position"),
            rs.getString("term"),
            rs.getLong("revision"),
            json.readValue(rs.getString("content_json"), VocabularyCatalog.LearningContent.class),
            rs.getObject("image_id", UUID.class),
            rs.getObject("uk_audio_id", UUID.class),
            rs.getObject("us_audio_id", UUID.class),
            rs.getObject("example_audio_id", UUID.class),
            rs.getObject("second_example_audio_id", UUID.class),
            rs.getString("progress_status")
        ));
    }

    @Override
    public Optional<EntryMediaAccessRow> findVisibleEntryForMedia(UUID entryId) {
        return jdbc.query("""
            SELECT e.id, e.image_id, e.uk_audio_id, e.us_audio_id, e.example_audio_id, e.second_example_audio_id,
                   p.access_mode, p.required_feature_key
            FROM vocabulary_unit_entry e
            JOIN vocabulary_unit u ON u.id = e.unit_id
            JOIN vocabulary_pack p ON p.id = u.pack_id
            WHERE e.id = :entryId AND NOT e.hidden AND NOT u.hidden AND NOT p.hidden
            """, Map.of("entryId", entryId), (rs, n) -> new EntryMediaAccessRow(
            rs.getObject("id", UUID.class),
            rs.getObject("image_id", UUID.class),
            rs.getObject("uk_audio_id", UUID.class),
            rs.getObject("us_audio_id", UUID.class),
            rs.getObject("example_audio_id", UUID.class),
            rs.getObject("second_example_audio_id", UUID.class),
            rs.getString("access_mode"),
            rs.getString("required_feature_key")
        )).stream().findFirst();
    }

    @Override
    public void upsertEntryProgress(UUID learnerId, UUID entryId, UUID unitId, UUID packId, boolean correct, Instant at) {
        jdbc.update("""
            INSERT INTO vocabulary_entry_progress (
                learner_id, entry_id, unit_id, pack_id, status, correct_count, attempt_count, last_practiced_at
            ) VALUES (
                :learnerId, :entryId, :unitId, :packId,
                CASE WHEN :correct THEN 'COMPLETED' ELSE 'IN_PROGRESS' END,
                CASE WHEN :correct THEN 1 ELSE 0 END,
                1,
                :at
            )
            ON CONFLICT (learner_id, entry_id) DO UPDATE SET
                correct_count = vocabulary_entry_progress.correct_count + CASE WHEN :correct THEN 1 ELSE 0 END,
                attempt_count = vocabulary_entry_progress.attempt_count + 1,
                status = CASE
                    WHEN vocabulary_entry_progress.status = 'COMPLETED' OR :correct THEN 'COMPLETED'
                    ELSE 'IN_PROGRESS'
                END,
                last_practiced_at = :at
            """, new MapSqlParameterSource()
            .addValue("learnerId", learnerId)
            .addValue("entryId", entryId)
            .addValue("unitId", unitId)
            .addValue("packId", packId)
            .addValue("correct", correct)
            .addValue("at", Timestamp.from(at)));
    }

    @Override
    public void insertSession(
        UUID id,
        UUID learnerId,
        UUID packId,
        UUID unitId,
        PracticeMode mode,
        String snapshotJson,
        Instant createdAt,
        Instant expiresAt
    ) {
        jdbc.update("""
            INSERT INTO vocabulary_study_session (
                id, learner_id, pack_id, unit_id, mode, snapshot, answers, revision, created_at, expires_at
            ) VALUES (
                :id, :learnerId, :packId, :unitId, :mode, CAST(:snapshot AS jsonb), '{}'::jsonb, 1, :createdAt, :expiresAt
            )
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("learnerId", learnerId)
            .addValue("packId", packId)
            .addValue("unitId", unitId)
            .addValue("mode", mode.name())
            .addValue("snapshot", snapshotJson)
            .addValue("createdAt", Timestamp.from(createdAt))
            .addValue("expiresAt", Timestamp.from(expiresAt)));
    }

    @Override
    public Optional<SessionRow> findSession(UUID id, UUID learnerId, boolean forUpdate) {
        String sql = """
            SELECT id, learner_id, pack_id, unit_id, mode,
                   snapshot::text AS snapshot_json, answers::text AS answers_json, result::text AS result_json,
                   revision, created_at, expires_at, submitted_at
            FROM vocabulary_study_session
            WHERE id = :id AND learner_id = :learnerId
            """ + (forUpdate ? " FOR UPDATE" : "");
        return jdbc.query(sql, Map.of("id", id, "learnerId", learnerId), (rs, n) -> mapSession(rs)).stream().findFirst();
    }

    @Override
    public List<SessionRow> findSessions(UUID learnerId, UUID unitId, String status) {
        var params = new MapSqlParameterSource()
            .addValue("learnerId", learnerId)
            .addValue("unitId", unitId)
            .addValue("status", status);
        return jdbc.query("""
            SELECT id, learner_id, pack_id, unit_id, mode,
                   snapshot::text AS snapshot_json, answers::text AS answers_json, result::text AS result_json,
                   revision, created_at, expires_at, submitted_at
            FROM vocabulary_study_session
            WHERE learner_id = :learnerId
              AND (CAST(:unitId AS uuid) IS NULL OR unit_id = :unitId)
              AND (CAST(:status AS text) IS NULL
                   OR (:status = 'SUBMITTED' AND submitted_at IS NOT NULL)
                   OR (:status = 'OPEN' AND submitted_at IS NULL AND expires_at > now())
                   OR (:status = 'EXPIRED' AND submitted_at IS NULL AND expires_at <= now()))
            ORDER BY created_at DESC
            LIMIT 100
            """, params, (rs, n) -> mapSession(rs));
    }

    @Override
    public int updateAnswers(UUID id, long expectedRevision, String answersJson) {
        return jdbc.update("""
            UPDATE vocabulary_study_session
            SET answers = CAST(:answers AS jsonb), revision = revision + 1
            WHERE id = :id AND revision = :revision AND submitted_at IS NULL
            """, Map.of("id", id, "revision", expectedRevision, "answers", answersJson));
    }

    @Override
    public int markSubmitted(UUID id, long expectedRevision, String resultJson, Instant submittedAt) {
        return jdbc.update("""
            UPDATE vocabulary_study_session
            SET result = CAST(:result AS jsonb), submitted_at = :submittedAt, revision = revision + 1
            WHERE id = :id AND revision = :revision AND submitted_at IS NULL
            """, new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("revision", expectedRevision)
            .addValue("result", resultJson)
            .addValue("submittedAt", Timestamp.from(submittedAt)));
    }

    private static SessionRow mapSession(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp submitted = rs.getTimestamp("submitted_at");
        return new SessionRow(
            rs.getObject("id", UUID.class),
            rs.getObject("learner_id", UUID.class),
            rs.getObject("pack_id", UUID.class),
            rs.getObject("unit_id", UUID.class),
            PracticeMode.valueOf(rs.getString("mode")),
            rs.getString("snapshot_json"),
            rs.getString("answers_json"),
            rs.getString("result_json"),
            rs.getLong("revision"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant(),
            submitted != null ? submitted.toInstant() : null
        );
    }
}
