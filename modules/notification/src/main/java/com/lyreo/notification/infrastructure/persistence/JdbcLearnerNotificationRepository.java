package com.lyreo.notification.infrastructure.persistence;

import com.lyreo.notification.application.port.LearnerNotificationRepository;
import com.lyreo.notification.domain.LearnerNotification;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public class JdbcLearnerNotificationRepository implements LearnerNotificationRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcLearnerNotificationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<LearnerNotification> listAll() {
        return jdbc.query(
            """
            SELECT id, title, body, category, target_audience, status,
                   published_at, expires_at, created_by, created_at, updated_at
            FROM learner_notification
            ORDER BY COALESCE(published_at, created_at) DESC, id DESC
            """,
            (rs, rowNum) -> mapRow(rs)
        );
    }

    @Override
    public List<LearnerNotification> listPublishedForAudience(String learnerTier) {
        String tier = "PLUS".equalsIgnoreCase(learnerTier) ? "PLUS" : "FREE";
        return jdbc.query(
            """
            SELECT id, title, body, category, target_audience, status,
                   published_at, expires_at, created_by, created_at, updated_at
            FROM learner_notification
            WHERE status = 'PUBLISHED'
              AND (target_audience = 'ALL' OR target_audience = :tier)
              AND (published_at IS NULL OR published_at <= now())
              AND (expires_at IS NULL OR expires_at > now())
            ORDER BY COALESCE(published_at, created_at) DESC, id DESC
            """,
            Map.of("tier", tier),
            (rs, rowNum) -> mapRow(rs)
        );
    }

    @Override
    public Optional<LearnerNotification> findById(UUID id) {
        return jdbc.query(
            """
            SELECT id, title, body, category, target_audience, status,
                   published_at, expires_at, created_by, created_at, updated_at
            FROM learner_notification
            WHERE id = :id
            """,
            Map.of("id", id),
            (rs, rowNum) -> mapRow(rs)
        ).stream().findFirst();
    }

    @Override
    public LearnerNotification insert(LearnerNotification item) {
        return jdbc.queryForObject(
            """
            INSERT INTO learner_notification(
                id, title, body, category, target_audience, status,
                published_at, expires_at, created_by, created_at, updated_at
            ) VALUES (
                :id, :title, :body, :category, :targetAudience, :status,
                CAST(:publishedAt AS timestamptz), CAST(:expiresAt AS timestamptz),
                :createdBy, now(), now()
            )
            RETURNING id, title, body, category, target_audience, status,
                      published_at, expires_at, created_by, created_at, updated_at
            """,
            params(item),
            (rs, rowNum) -> mapRow(rs)
        );
    }

    @Override
    public Optional<LearnerNotification> update(LearnerNotification item) {
        return jdbc.query(
            """
            UPDATE learner_notification
            SET title = :title,
                body = :body,
                category = :category,
                target_audience = :targetAudience,
                status = :status,
                published_at = CAST(:publishedAt AS timestamptz),
                expires_at = CAST(:expiresAt AS timestamptz),
                updated_at = now()
            WHERE id = :id
            RETURNING id, title, body, category, target_audience, status,
                      published_at, expires_at, created_by, created_at, updated_at
            """,
            params(item),
            (rs, rowNum) -> mapRow(rs)
        ).stream().findFirst();
    }

    @Override
    public boolean delete(UUID id) {
        return jdbc.update("DELETE FROM learner_notification WHERE id = :id", Map.of("id", id)) > 0;
    }

    private static MapSqlParameterSource params(LearnerNotification item) {
        return new MapSqlParameterSource()
            .addValue("id", item.id())
            .addValue("title", item.title())
            .addValue("body", item.body())
            .addValue("category", item.category())
            .addValue("targetAudience", item.targetAudience())
            .addValue("status", item.status())
            .addValue("publishedAt", item.publishedAt() == null ? null : Timestamp.from(item.publishedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
            .addValue("expiresAt", item.expiresAt() == null ? null : Timestamp.from(item.expiresAt()), Types.TIMESTAMP_WITH_TIMEZONE)
            .addValue("createdBy", item.createdBy());
    }

    private static LearnerNotification mapRow(ResultSet rs) throws SQLException {
        return new LearnerNotification(
            rs.getObject("id", UUID.class),
            rs.getString("title"),
            rs.getString("body"),
            rs.getString("category"),
            rs.getString("target_audience"),
            rs.getString("status"),
            toInstant(rs.getTimestamp("published_at")),
            toInstant(rs.getTimestamp("expires_at")),
            rs.getString("created_by"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
