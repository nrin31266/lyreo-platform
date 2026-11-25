package com.lyreo.curriculum.infrastructure.persistence;

import com.lyreo.curriculum.application.port.CurriculumProgressRepository;
import com.lyreo.curriculum.domain.CurriculumEnrollment;
import com.lyreo.curriculum.domain.CurriculumItem;
import com.lyreo.curriculum.domain.CurriculumItem.ContentType;
import com.lyreo.curriculum.domain.CurriculumItemProgress;
import com.lyreo.curriculum.domain.CurriculumPath;
import com.lyreo.curriculum.domain.CurriculumPathSummary;
import com.lyreo.curriculum.domain.CurriculumSection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcCurriculumProgressRepository implements CurriculumProgressRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCurriculumProgressRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
            SELECT id, code, name, level, description, status, created_at, updated_at
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
            SELECT id, code, name, level, description, status, created_at, updated_at
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
                   ci.content_reference_id, ci.required, ci.unlock_rule
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
                rs.getString("unlock_rule")
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
        String status
    ) {
        return jdbc.queryForObject(
            """
            INSERT INTO curriculum_path(id, code, name, level, description, status, created_at, updated_at)
            VALUES (:id, :code, :name, :level, :description, :status, now(), now())
            RETURNING id, code, name, level, description, status, created_at, updated_at
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("code", code)
                .addValue("name", name)
                .addValue("level", level)
                .addValue("description", description)
                .addValue("status", status),
            (rs, rowNum) -> mapPath(rs)
        );
    }

    @Override
    public Optional<CurriculumPath> updatePath(
        UUID id,
        String code,
        String name,
        String level,
        String description
    ) {
        return jdbc.query(
            """
            UPDATE curriculum_path
            SET code = :code,
                name = :name,
                level = :level,
                description = :description,
                updated_at = now()
            WHERE id = :id
            RETURNING id, code, name, level, description, status, created_at, updated_at
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("code", code)
                .addValue("name", name)
                .addValue("level", level)
                .addValue("description", description),
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
            RETURNING id, code, name, level, description, status, created_at, updated_at
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
            RETURNING id, section_id, "position", content_type, content_reference_id, required, unlock_rule
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
                rs.getString("unlock_rule")
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

    private static CurriculumPath mapPath(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CurriculumPath(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("level"),
            rs.getString("description"),
            rs.getString("status"),
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
