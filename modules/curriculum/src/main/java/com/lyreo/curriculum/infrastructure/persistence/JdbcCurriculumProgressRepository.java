package com.lyreo.curriculum.infrastructure.persistence;

import com.lyreo.curriculum.application.port.CurriculumProgressRepository;
import com.lyreo.curriculum.domain.CurriculumEnrollment;
import com.lyreo.curriculum.domain.CurriculumItem;
import com.lyreo.curriculum.domain.CurriculumItem.ContentType;
import com.lyreo.curriculum.domain.CurriculumItemProgress;
import com.lyreo.curriculum.domain.CurriculumPath;
import com.lyreo.curriculum.domain.CurriculumPathDiagnostic;
import com.lyreo.curriculum.domain.CurriculumPathSummary;
import com.lyreo.curriculum.domain.CurriculumSection;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

public final class JdbcCurriculumProgressRepository implements CurriculumProgressRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcCurriculumProgressRepository(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, new ObjectMapper());
    }

    public JdbcCurriculumProgressRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    @Override
    public List<UUID> itemIdsReferencing(ContentType type, UUID referenceId) {
        return jdbc.query(
            """
            SELECT ci.id
            FROM curriculum_item ci
            JOIN curriculum_section cs ON cs.id = ci.section_id
            JOIN curriculum_path cp ON cp.id = cs.path_id
            WHERE ci.content_type = :type
              AND ci.content_reference_id = :referenceId
              AND cp.status = 'PUBLISHED'
            """,
            Map.of("type", type.name(), "referenceId", referenceId),
            (rs, rowNumber) -> rs.getObject("id", UUID.class)
        );
    }

    @Override
    public List<ActiveItemTarget> activeEnrolledItemTargetsReferencing(
        UUID learnerId,
        ContentType type,
        UUID contentReferenceId
    ) {
        return jdbc.query(
            """
            SELECT ci.id AS item_id, cp.id AS path_id
            FROM curriculum_item ci
            JOIN curriculum_section cs ON cs.id = ci.section_id
            JOIN curriculum_path cp ON cp.id = cs.path_id
            JOIN curriculum_enrollment ce ON ce.path_id = cp.id AND ce.learner_id = :learnerId
            WHERE ci.content_type = :type
              AND ci.content_reference_id = :referenceId
              AND cp.status = 'PUBLISHED'
              AND ce.status = 'ACTIVE'
            """,
            Map.of(
                "learnerId", learnerId,
                "type", type.name(),
                "referenceId", contentReferenceId
            ),
            (rs, rowNumber) -> new ActiveItemTarget(
                rs.getObject("item_id", UUID.class),
                rs.getObject("path_id", UUID.class)
            )
        );
    }

    @Override
    public void complete(UUID learnerId, UUID itemId) {
        jdbc.update(
            """
            INSERT INTO curriculum_item_progress(
                learner_id, item_id, status, started_at, completed_at
            )
            SELECT :learnerId, ci.id, 'COMPLETED', now(), now()
            FROM curriculum_item ci
            JOIN curriculum_section cs ON cs.id = ci.section_id
            JOIN curriculum_path cp ON cp.id = cs.path_id
            JOIN curriculum_enrollment ce ON ce.path_id = cp.id AND ce.learner_id = :learnerId
            WHERE ci.id = :itemId
              AND cp.status = 'PUBLISHED'
              AND ce.status = 'ACTIVE'
            ON CONFLICT(learner_id, item_id) DO UPDATE SET
                status = 'COMPLETED',
                started_at = COALESCE(curriculum_item_progress.started_at, now()),
                completed_at = COALESCE(curriculum_item_progress.completed_at, now())
            """,
            Map.of("learnerId", learnerId, "itemId", itemId)
        );
    }

    @Override
    public void upsertItemProgressStatus(UUID learnerId, UUID itemId, String status) {
        jdbc.update(
            """
            INSERT INTO curriculum_item_progress(learner_id, item_id, status)
            VALUES (:learnerId, :itemId, :status)
            ON CONFLICT(learner_id, item_id) DO UPDATE SET
                status = CASE
                    WHEN curriculum_item_progress.status IN ('COMPLETED', 'IN_PROGRESS')
                        THEN curriculum_item_progress.status
                    ELSE excluded.status
                END
            """,
            Map.of("learnerId", learnerId, "itemId", itemId, "status", status)
        );
    }

    @Override
    public Optional<CurriculumPath> findPathById(UUID pathId) {
        return jdbc.query(
            """
            SELECT id, code, name, level, description, status, cover_asset_id, access_mode, required_feature_key, created_at, updated_at
            FROM curriculum_path
            WHERE id = :pathId
            """,
            Map.of("pathId", pathId),
            (rs, rowNum) -> mapPath(rs)
        ).stream().findFirst();
    }

    @Override
    public List<CurriculumPathSummary> listPublishedPaths(UUID learnerId) {
        return jdbc.query(
            """
            SELECT
                cp.id,
                cp.code,
                cp.name,
                cp.level,
                cp.description,
                cp.status,
                cp.cover_asset_id,
                cp.access_mode,
                cp.required_feature_key,
                ce.status AS enrollment_status,
                COUNT(ci.id)::int AS total_items,
                COUNT(ci.id) FILTER (WHERE ci.required)::int AS required_items,
                COUNT(cip.item_id) FILTER (WHERE ci.required AND cip.status = 'COMPLETED')::int AS completed_required_items,
                cp.created_at,
                cp.updated_at
            FROM curriculum_path cp
            LEFT JOIN curriculum_enrollment ce
                ON ce.path_id = cp.id AND ce.learner_id = :learnerId
            LEFT JOIN curriculum_section cs ON cs.path_id = cp.id
            LEFT JOIN curriculum_item ci ON ci.section_id = cs.id
            LEFT JOIN curriculum_item_progress cip
                ON cip.item_id = ci.id AND cip.learner_id = :learnerId
            WHERE cp.status = 'PUBLISHED'
            GROUP BY cp.id, ce.status
            ORDER BY cp.created_at ASC, cp.code ASC
            """,
            new MapSqlParameterSource("learnerId", learnerId),
            (rs, rowNum) -> new CurriculumPathSummary(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("level"),
                rs.getString("description"),
                rs.getString("status"),
                rs.getObject("cover_asset_id", UUID.class),
                rs.getString("access_mode"),
                rs.getString("required_feature_key"),
                false,
                rs.getString("enrollment_status"),
                rs.getInt("total_items"),
                rs.getInt("required_items"),
                rs.getInt("completed_required_items"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
            )
        );
    }

    @Override
    public List<CurriculumPath> listAllPaths() {
        return jdbc.query(
            """
            SELECT id, code, name, level, description, status, cover_asset_id, access_mode, required_feature_key, created_at, updated_at
            FROM curriculum_path
            ORDER BY created_at DESC, code ASC
            """,
            (rs, rowNum) -> mapPath(rs)
        );
    }

    @Override
    public List<CurriculumSection> findSectionsByPathId(UUID pathId) {
        return jdbc.query(
            """
            SELECT id, path_id, title, "position"
            FROM curriculum_section
            WHERE path_id = :pathId
            ORDER BY "position" ASC
            """,
            Map.of("pathId", pathId),
            (rs, rowNum) -> new CurriculumSection(
                rs.getObject("id", UUID.class),
                rs.getObject("path_id", UUID.class),
                rs.getString("title"),
                rs.getInt("position")
            )
        );
    }

    @Override
    public List<CurriculumItem> findItemsByPathId(UUID pathId) {
        return jdbc.query(
            """
            SELECT ci.id, ci.section_id, ci."position", ci.content_type,
                   ci.content_reference_id, ci.required, ci.unlock_rule, ci.config_json
            FROM curriculum_item ci
            JOIN curriculum_section cs ON cs.id = ci.section_id
            WHERE cs.path_id = :pathId
            ORDER BY cs."position" ASC, ci."position" ASC
            """,
            Map.of("pathId", pathId),
            (rs, rowNum) -> new CurriculumItem(
                rs.getObject("id", UUID.class),
                rs.getObject("section_id", UUID.class),
                rs.getInt("position"),
                ContentType.valueOf(rs.getString("content_type")),
                rs.getObject("content_reference_id", UUID.class),
                rs.getBoolean("required"),
                rs.getString("unlock_rule"),
                decodeConfig(rs.getString("config_json"))
            )
        );
    }

    @Override
    public Optional<CurriculumEnrollment> findEnrollment(UUID learnerId, UUID pathId) {
        return jdbc.query(
            """
            SELECT learner_id, path_id, status, started_at, completed_at
            FROM curriculum_enrollment
            WHERE learner_id = :learnerId AND path_id = :pathId
            """,
            Map.of("learnerId", learnerId, "pathId", pathId),
            (rs, rowNum) -> mapEnrollment(rs)
        ).stream().findFirst();
    }

    @Override
    public List<CurriculumEnrollment> findActivePublishedEnrollments(UUID learnerId) {
        return jdbc.query(
            """
            SELECT ce.learner_id, ce.path_id, ce.status, ce.started_at, ce.completed_at
            FROM curriculum_enrollment ce
            JOIN curriculum_path cp ON cp.id = ce.path_id
            WHERE ce.learner_id = :learnerId
              AND ce.status = 'ACTIVE'
              AND cp.status = 'PUBLISHED'
            ORDER BY ce.started_at DESC
            """,
            Map.of("learnerId", learnerId),
            (rs, rowNum) -> mapEnrollment(rs)
        );
    }

    @Override
    public List<CurriculumItemProgress> findItemProgressByPath(UUID learnerId, UUID pathId) {
        return jdbc.query(
            """
            SELECT cip.learner_id, cip.item_id, cip.status, cip.started_at, cip.completed_at
            FROM curriculum_item_progress cip
            JOIN curriculum_item ci ON ci.id = cip.item_id
            JOIN curriculum_section cs ON cs.id = ci.section_id
            WHERE cip.learner_id = :learnerId
              AND cs.path_id = :pathId
            """,
            Map.of("learnerId", learnerId, "pathId", pathId),
            (rs, rowNum) -> new CurriculumItemProgress(
                rs.getObject("learner_id", UUID.class),
                rs.getObject("item_id", UUID.class),
                rs.getString("status"),
                toInstant(rs.getTimestamp("started_at")),
                toInstant(rs.getTimestamp("completed_at"))
            )
        );
    }

    @Override
    public void ensureActiveEnrollment(UUID learnerId, UUID pathId) {
        jdbc.update(
            """
            INSERT INTO curriculum_enrollment(learner_id, path_id, status, started_at)
            VALUES (:learnerId, :pathId, 'ACTIVE', now())
            ON CONFLICT(learner_id, path_id) DO NOTHING
            """,
            Map.of("learnerId", learnerId, "pathId", pathId)
        );
    }

    @Override
    public void markEnrollmentCompleted(UUID learnerId, UUID pathId) {
        jdbc.update(
            """
            UPDATE curriculum_enrollment
            SET status = 'COMPLETED',
                completed_at = COALESCE(completed_at, now())
            WHERE learner_id = :learnerId
              AND path_id = :pathId
              AND status = 'ACTIVE'
            """,
            Map.of("learnerId", learnerId, "pathId", pathId)
        );
    }

    @Override
    public CurriculumPath insertPath(
        UUID id,
        String code,
        String name,
        String level,
        String description,
        String status,
        UUID coverAssetId,
        String accessMode,
        String requiredFeatureKey
    ) {
        String mode = accessMode == null || accessMode.isBlank() ? "PUBLIC" : accessMode.trim();
        String feature = "FEATURE".equals(mode) ? requiredFeatureKey : null;
        return jdbc.queryForObject(
            """
            INSERT INTO curriculum_path(
                id, code, name, level, description, status, cover_asset_id, access_mode, required_feature_key, created_at, updated_at
            )
            VALUES (
                :id, :code, :name, :level, :description, :status,
                CAST(:coverAssetId AS uuid), :accessMode, CAST(:requiredFeatureKey AS varchar), now(), now()
            )
            RETURNING id, code, name, level, description, status, cover_asset_id, access_mode, required_feature_key, created_at, updated_at
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("code", code)
                .addValue("name", name)
                .addValue("level", level)
                .addValue("description", description)
                .addValue("status", status)
                .addValue("coverAssetId", coverAssetId, Types.OTHER)
                .addValue("accessMode", mode)
                .addValue("requiredFeatureKey", feature, Types.VARCHAR),
            (rs, rowNum) -> mapPath(rs)
        );
    }

    @Override
    public Optional<CurriculumPath> updatePath(
        UUID id,
        String code,
        String name,
        String level,
        String description,
        UUID coverAssetId,
        String accessMode,
        String requiredFeatureKey
    ) {
        String mode = accessMode == null || accessMode.isBlank() ? null : accessMode.trim();
        String feature = mode == null ? null : ("FEATURE".equals(mode) ? requiredFeatureKey : null);
        return jdbc.query(
            """
            UPDATE curriculum_path
            SET code = :code,
                name = :name,
                level = :level,
                description = :description,
                cover_asset_id = COALESCE(CAST(:coverAssetId AS uuid), cover_asset_id),
                access_mode = COALESCE(CAST(:accessMode AS varchar), access_mode),
                required_feature_key = CASE
                    WHEN CAST(:accessMode AS varchar) = 'PUBLIC' THEN NULL
                    WHEN CAST(:accessMode AS varchar) = 'FEATURE' THEN CAST(:requiredFeatureKey AS varchar)
                    ELSE required_feature_key
                END,
                updated_at = now()
            WHERE id = :id
            RETURNING id, code, name, level, description, status, cover_asset_id, access_mode, required_feature_key, created_at, updated_at
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("code", code)
                .addValue("name", name)
                .addValue("level", level)
                .addValue("description", description)
                .addValue("coverAssetId", coverAssetId, Types.OTHER)
                .addValue("accessMode", mode, Types.VARCHAR)
                .addValue("requiredFeatureKey", feature, Types.VARCHAR),
            (rs, rowNum) -> mapPath(rs)
        ).stream().findFirst();
    }

    @Override
    public Optional<CurriculumPath> updatePathStatus(UUID id, String status) {
        return jdbc.query(
            """
            UPDATE curriculum_path
            SET status = :status,
                updated_at = now()
            WHERE id = :id
            RETURNING id, code, name, level, description, status, cover_asset_id, access_mode, required_feature_key, created_at, updated_at
            """,
            Map.of("id", id, "status", status),
            (rs, rowNum) -> mapPath(rs)
        ).stream().findFirst();
    }

    @Override
    public boolean deletePath(UUID id) {
        return jdbc.update("DELETE FROM curriculum_path WHERE id = :id", Map.of("id", id)) > 0;
    }

    @Override
    public CurriculumSection insertSection(UUID id, UUID pathId, String title, int position) {
        return jdbc.queryForObject(
            """
            INSERT INTO curriculum_section(id, path_id, title, "position")
            VALUES (:id, :pathId, :title, :position)
            RETURNING id, path_id, title, "position"
            """,
            Map.of("id", id, "pathId", pathId, "title", title, "position", position),
            (rs, rowNum) -> new CurriculumSection(
                rs.getObject("id", UUID.class),
                rs.getObject("path_id", UUID.class),
                rs.getString("title"),
                rs.getInt("position")
            )
        );
    }

    @Override
    public CurriculumItem insertItem(
        UUID id,
        UUID sectionId,
        int position,
        ContentType contentType,
        UUID contentReferenceId,
        boolean required,
        String unlockRule,
        String configJson
    ) {
        return jdbc.queryForObject(
            """
            INSERT INTO curriculum_item(
                id, section_id, "position", content_type, content_reference_id,
                required, unlock_rule, config_json
            )
            VALUES (
                :id, :sectionId, :position, :contentType, :contentReferenceId,
                :required, :unlockRule, CAST(:configJson AS jsonb)
            )
            RETURNING id, section_id, "position", content_type, content_reference_id, required, unlock_rule, config_json
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("sectionId", sectionId)
                .addValue("position", position)
                .addValue("contentType", contentType.name())
                .addValue("contentReferenceId", contentReferenceId)
                .addValue("required", required)
                .addValue("unlockRule", unlockRule)
                .addValue("configJson", configJson == null || configJson.isBlank() ? "{}" : configJson),
            (rs, rowNum) -> new CurriculumItem(
                rs.getObject("id", UUID.class),
                rs.getObject("section_id", UUID.class),
                rs.getInt("position"),
                ContentType.valueOf(rs.getString("content_type")),
                rs.getObject("content_reference_id", UUID.class),
                rs.getBoolean("required"),
                rs.getString("unlock_rule"),
                decodeConfig(rs.getString("config_json"))
            )
        );
    }

    @Override
    public void deleteSectionsByPathId(UUID pathId) {
        jdbc.update("DELETE FROM curriculum_section WHERE path_id = :pathId", Map.of("pathId", pathId));
    }

    @Override
    public Optional<CurriculumSection> findSectionById(UUID sectionId) {
        return jdbc.query(
            """
            SELECT id, path_id, title, "position"
            FROM curriculum_section
            WHERE id = :sectionId
            """,
            Map.of("sectionId", sectionId),
            (rs, rowNum) -> new CurriculumSection(
                rs.getObject("id", UUID.class),
                rs.getObject("path_id", UUID.class),
                rs.getString("title"),
                rs.getInt("position")
            )
        ).stream().findFirst();
    }

    @Override
    public boolean existsByCode(String code, UUID excludePathId) {
        Integer count = jdbc.queryForObject(
            """
            SELECT count(*)
            FROM curriculum_path
            WHERE code = :code
              AND (:excludePathId::uuid IS NULL OR id <> :excludePathId::uuid)
            """,
            new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("excludePathId", excludePathId),
            Integer.class
        );
        return count != null && count > 0;
    }

    @Override
    public CurriculumPathDiagnostic diagnosePath(UUID pathId) {
        record Row(
            UUID itemId,
            int sectionPos,
            int itemPos,
            ContentType type,
            UUID refId,
            Map<String, Object> config
        ) {}
        List<Row> rows = jdbc.query(
            """
            SELECT ci.id AS item_id, cs."position" AS section_pos, ci."position" AS item_pos,
                   ci.content_type, ci.content_reference_id, ci.config_json
            FROM curriculum_item ci
            JOIN curriculum_section cs ON cs.id = ci.section_id
            WHERE cs.path_id = :pathId
            ORDER BY cs."position" ASC, ci."position" ASC
            """,
            Map.of("pathId", pathId),
            (rs, rowNum) -> new Row(
                rs.getObject("item_id", UUID.class),
                rs.getInt("section_pos"),
                rs.getInt("item_pos"),
                ContentType.valueOf(rs.getString("content_type")),
                rs.getObject("content_reference_id", UUID.class),
                decodeConfig(rs.getString("config_json"))
            )
        );
        List<CurriculumPathDiagnostic.ItemCheck> checks = new ArrayList<>(rows.size());
        int valid = 0;
        int missing = 0;
        int lockedForFree = 0;
        for (Row row : rows) {
            CurriculumPathDiagnostic.ItemCheck check = checkItem(row.itemId(), row.sectionPos(), row.itemPos(), row.type(), row.refId(), row.config());
            checks.add(check);
            if (check.exists() && check.sliceValid()) {
                valid++;
            }
            if (!check.exists()) {
                missing++;
            }
            if ("FEATURE".equals(check.accessMode())) {
                lockedForFree++;
            }
        }
        return new CurriculumPathDiagnostic(pathId, rows.size(), valid, missing, lockedForFree, checks);
    }

    private CurriculumPathDiagnostic.ItemCheck checkItem(
        UUID itemId,
        int sectionPos,
        int itemPos,
        ContentType type,
        UUID refId,
        Map<String, Object> config
    ) {
        return switch (type) {
            case LESSON -> checkLesson(itemId, sectionPos, itemPos, type, refId, config);
            case VOCABULARY_REVIEW -> checkVocabulary(itemId, sectionPos, itemPos, type, refId, config);
            case GRAMMAR_PRACTICE -> checkGrammar(itemId, sectionPos, itemPos, type, refId);
            case TOEIC_DRILL, TOEIC_TEST -> checkToeic(itemId, sectionPos, itemPos, type, refId, config);
        };
    }

    private CurriculumPathDiagnostic.ItemCheck checkLesson(
        UUID itemId, int sectionPos, int itemPos, ContentType type, UUID refId, Map<String, Object> config
    ) {
        var found = jdbc.query(
            """
            SELECT m.title, l.status, m.access_mode, m.required_feature_key
            FROM lesson l
            JOIN lesson_metadata m ON m.lesson_id = l.id AND m.deleted_at IS NULL
            WHERE l.id = :refId
            """,
            Map.of("refId", refId),
            (rs, n) -> new Object[] {
                rs.getString("title"), rs.getString("status"),
                rs.getString("access_mode"), rs.getString("required_feature_key")
            }
        );
        if (found.isEmpty()) {
            return new CurriculumPathDiagnostic.ItemCheck(itemId, sectionPos, itemPos, type, refId, null, false, "PUBLIC", null, false, 0, "Referenced lesson not found");
        }
        String title = (String) found.getFirst()[0];
        String status = (String) found.getFirst()[1];
        String accessMode = (String) found.getFirst()[2];
        String featureKey = (String) found.getFirst()[3];
        List<UUID> sentenceIds = extractUuidList(config.get("sentenceIds"));
        int sliceCount;
        boolean sliceValid;
        String msg;
        if (!sentenceIds.isEmpty()) {
            Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM lesson_sentence WHERE lesson_id = :refId AND id IN (:ids)",
                new MapSqlParameterSource("refId", refId).addValue("ids", sentenceIds),
                Integer.class
            );
            sliceCount = count == null ? 0 : count;
            sliceValid = sliceCount == sentenceIds.size();
            msg = sliceValid ? ("OK (" + status + ", " + sliceCount + " sentences)") : ("Expected " + sentenceIds.size() + " curated sentences, found " + sliceCount);
        } else {
            Integer count = jdbc.queryForObject("SELECT count(*) FROM lesson_sentence WHERE lesson_id = :refId", Map.of("refId", refId), Integer.class);
            sliceCount = count == null ? 0 : count;
            sliceValid = "PUBLISHED".equals(status);
            msg = sliceValid ? "OK (Published lesson)" : ("Lesson status is " + status);
        }
        return new CurriculumPathDiagnostic.ItemCheck(itemId, sectionPos, itemPos, type, refId, title, true, accessMode, featureKey, sliceValid, sliceCount, msg);
    }

    private CurriculumPathDiagnostic.ItemCheck checkVocabulary(
        UUID itemId, int sectionPos, int itemPos, ContentType type, UUID refId, Map<String, Object> config
    ) {
        var unitFound = jdbc.query(
            """
            SELECT u.title, p.title_en, p.access_mode, p.required_feature_key
            FROM vocabulary_unit u
            JOIN vocabulary_pack p ON p.id = u.pack_id
            WHERE u.id = :refId
            """,
            Map.of("refId", refId),
            (rs, n) -> new Object[] {
                rs.getString("title_en") + " · " + rs.getString("title"),
                rs.getString("access_mode"),
                rs.getString("required_feature_key"),
                true
            }
        );
        if (unitFound.isEmpty()) {
            unitFound = jdbc.query(
                "SELECT title_en, access_mode, required_feature_key FROM vocabulary_pack WHERE id = :refId",
                Map.of("refId", refId),
                (rs, n) -> new Object[] {
                    rs.getString("title_en"),
                    rs.getString("access_mode"),
                    rs.getString("required_feature_key"),
                    false
                }
            );
        }
        if (unitFound.isEmpty()) {
            return new CurriculumPathDiagnostic.ItemCheck(itemId, sectionPos, itemPos, type, refId, null, false, "PUBLIC", null, false, 0, "Referenced vocabulary pack/unit not found");
        }
        String title = (String) unitFound.getFirst()[0];
        String accessMode = (String) unitFound.getFirst()[1];
        String featureKey = (String) unitFound.getFirst()[2];
        boolean isUnit = (Boolean) unitFound.getFirst()[3];
        List<UUID> entryIds = extractUuidList(config.get("entryIds"));
        int sliceCount;
        boolean sliceValid;
        String msg;
        if (!entryIds.isEmpty()) {
            Integer count = jdbc.queryForObject(
                isUnit
                    ? "SELECT count(*) FROM vocabulary_unit_entry WHERE unit_id = :refId AND id IN (:ids)"
                    : "SELECT count(*) FROM vocabulary_unit_entry e JOIN vocabulary_unit u ON u.id = e.unit_id WHERE u.pack_id = :refId AND e.id IN (:ids)",
                new MapSqlParameterSource("refId", refId).addValue("ids", entryIds),
                Integer.class
            );
            sliceCount = count == null ? 0 : count;
            sliceValid = sliceCount == entryIds.size();
            msg = sliceValid ? ("OK (" + sliceCount + " curated words)") : ("Expected " + entryIds.size() + " words, found " + sliceCount);
        } else {
            Integer count = jdbc.queryForObject(
                isUnit
                    ? "SELECT count(*) FROM vocabulary_unit_entry WHERE unit_id = :refId"
                    : "SELECT count(*) FROM vocabulary_unit_entry e JOIN vocabulary_unit u ON u.id = e.unit_id WHERE u.pack_id = :refId",
                Map.of("refId", refId),
                Integer.class
            );
            sliceCount = count == null ? 0 : count;
            sliceValid = sliceCount > 0;
            msg = sliceValid ? ("OK (" + sliceCount + " words)") : "No entries in vocabulary unit";
        }
        return new CurriculumPathDiagnostic.ItemCheck(itemId, sectionPos, itemPos, type, refId, title, true, accessMode, featureKey, sliceValid, sliceCount, msg);
    }

    private CurriculumPathDiagnostic.ItemCheck checkGrammar(
        UUID itemId, int sectionPos, int itemPos, ContentType type, UUID refId
    ) {
        var found = jdbc.query(
            """
            SELECT c.publication_status, c.access_mode, c.required_feature_key,
                   COALESCE((SELECT v.title_en FROM grammar_topic_version v WHERE v.catalog_id = c.id LIMIT 1), 'Grammar Topic') AS title
            FROM grammar_topic_catalog c
            WHERE c.id = :refId
            """,
            Map.of("refId", refId),
            (rs, n) -> new Object[] {
                rs.getString("title"), rs.getString("publication_status"),
                rs.getString("access_mode"), rs.getString("required_feature_key")
            }
        );
        if (found.isEmpty()) {
            return new CurriculumPathDiagnostic.ItemCheck(itemId, sectionPos, itemPos, type, refId, null, false, "PUBLIC", null, false, 0, "Referenced grammar topic not found");
        }
        String title = (String) found.getFirst()[0];
        String status = (String) found.getFirst()[1];
        String accessMode = (String) found.getFirst()[2];
        String featureKey = (String) found.getFirst()[3];
        boolean valid = "PUBLISHED".equals(status);
        return new CurriculumPathDiagnostic.ItemCheck(itemId, sectionPos, itemPos, type, refId, title, true, accessMode, featureKey, valid, 1, valid ? "OK" : ("Grammar topic status: " + status));
    }

    private CurriculumPathDiagnostic.ItemCheck checkToeic(
        UUID itemId, int sectionPos, int itemPos, ContentType type, UUID refId, Map<String, Object> config
    ) {
        var found = jdbc.query(
            """
            SELECT c.publication_status, c.access_mode, c.required_feature_key,
                   COALESCE((SELECT v.name FROM toeic_test_version v WHERE v.catalog_id = c.id LIMIT 1), 'TOEIC ' || c.year || ' Test ' || c.test_number) AS title
            FROM toeic_test_catalog c
            WHERE c.id = :refId
            """,
            Map.of("refId", refId),
            (rs, n) -> new Object[] {
                rs.getString("title"), rs.getString("publication_status"),
                rs.getString("access_mode"), rs.getString("required_feature_key")
            }
        );
        if (found.isEmpty()) {
            return new CurriculumPathDiagnostic.ItemCheck(itemId, sectionPos, itemPos, type, refId, null, false, "PUBLIC", null, false, 0, "Referenced TOEIC test not found");
        }
        String title = (String) found.getFirst()[0];
        String status = (String) found.getFirst()[1];
        String accessMode = (String) found.getFirst()[2];
        String featureKey = (String) found.getFirst()[3];
        List<Integer> parts = extractIntList(config.get("parts"));
        Integer avail = jdbc.queryForObject(
            parts.isEmpty()
                ? """
                  SELECT count(*) FROM toeic_placement tp
                  JOIN toeic_test_version tv ON tv.id = tp.test_version_id
                  WHERE tv.catalog_id = :refId
                  """
                : """
                  SELECT count(*) FROM toeic_placement tp
                  JOIN toeic_test_version tv ON tv.id = tp.test_version_id
                  WHERE tv.catalog_id = :refId AND tp.part IN (:parts)
                  """,
            parts.isEmpty() ? new MapSqlParameterSource("refId", refId) : new MapSqlParameterSource("refId", refId).addValue("parts", parts),
            Integer.class
        );
        int totalAvail = avail == null ? 0 : avail;
        int requestedCount = config.get("questionCount") instanceof Number num ? num.intValue() : totalAvail;
        boolean sliceValid = "PUBLISHED".equals(status) && totalAvail > 0 && requestedCount > 0 && requestedCount <= totalAvail;
        String msg = sliceValid
            ? ("OK (" + Math.min(requestedCount, totalAvail) + " / " + totalAvail + " questions)")
            : ("TOEIC status=" + status + ", available=" + totalAvail + ", requested=" + requestedCount);
        return new CurriculumPathDiagnostic.ItemCheck(itemId, sectionPos, itemPos, type, refId, title, true, accessMode, featureKey, sliceValid, Math.min(requestedCount, totalAvail), msg);
    }

    private Map<String, Object> decodeConfig(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = mapper.readValue(json, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static List<UUID> extractUuidList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<UUID> out = new ArrayList<>();
        for (Object entry : list) {
            if (entry != null) {
                try {
                    out.add(UUID.fromString(entry.toString()));
                } catch (IllegalArgumentException ignored) {
                    // ignore malformed slice entry
                }
            }
        }
        return out;
    }

    private static List<Integer> extractIntList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Number num) {
                out.add(num.intValue());
            } else if (entry != null) {
                try {
                    out.add(Integer.parseInt(entry.toString()));
                } catch (NumberFormatException ignored) {
                    // ignore non-numeric entry
                }
            }
        }
        return out;
    }

    private static CurriculumPath mapPath(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CurriculumPath(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("level"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getObject("cover_asset_id", UUID.class),
            rs.getString("access_mode"),
            rs.getString("required_feature_key"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private static CurriculumEnrollment mapEnrollment(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CurriculumEnrollment(
            rs.getObject("learner_id", UUID.class),
            rs.getObject("path_id", UUID.class),
            rs.getString("status"),
            toInstant(rs.getTimestamp("started_at")),
            toInstant(rs.getTimestamp("completed_at"))
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
