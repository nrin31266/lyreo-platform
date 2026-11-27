package com.lyreo.notification.infrastructure.persistence;

import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.notification.application.port.LearnerNotificationRepository;
import com.lyreo.notification.domain.AdminActivity;
import com.lyreo.notification.domain.LearnerNotification;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public class JdbcLearnerNotificationRepository implements LearnerNotificationRepository {
    private static final String SELECT = """
        SELECT n.*,
               ARRAY(SELECT r.learner_id FROM learner_notification_recipient r
                     WHERE r.notification_id = n.id ORDER BY r.learner_id) AS recipients,
               ARRAY(SELECT COALESCE(u.display_name, u.email_snapshot, r.learner_id::text)
                     FROM learner_notification_recipient r LEFT JOIN notification_learner_directory u ON u.id = r.learner_id
                     WHERE r.notification_id = n.id ORDER BY r.learner_id) AS recipient_labels
        FROM learner_notification n
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcLearnerNotificationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<LearnerNotification> listAll() {
        return jdbc.query(SELECT + "ORDER BY COALESCE(published_at, created_at) DESC, id DESC", Map.of(),
            (rs, rowNum) -> mapRow(rs, true));
    }

    @Override
    public List<LearnerNotification> listPublishedForAudience(UUID learnerId, String learnerTier) {
        return listPublishedForAudience(learnerId, learnerTier, false, 0, 500);
    }

    @Override
    public List<LearnerNotification> listPublishedForAudience(
        UUID learnerId,
        String learnerTier,
        boolean unreadOnly,
        int offset,
        int limit
    ) {
        String unreadFilter = unreadOnly ? " AND nr.read_at IS NULL " : "";
        return jdbc.query("""
            SELECT n.*,
                   (nr.read_at IS NOT NULL) AS is_read,
                   nr.read_at AS learner_read_at
            FROM learner_notification n
            LEFT JOIN learner_notification_read nr
              ON nr.notification_id = n.id AND nr.learner_id = :learnerId
            WHERE n.status = 'PUBLISHED'
              AND (n.target_audience IN ('ALL', :tier) OR (n.target_audience = 'SELECTED' AND EXISTS (
                    SELECT 1 FROM learner_notification_recipient r
                    WHERE r.notification_id = n.id AND r.learner_id = :learnerId)))
              AND (n.published_at IS NULL OR n.published_at <= now())
              AND (n.expires_at IS NULL OR n.expires_at > now())
            """ + unreadFilter + """
            ORDER BY COALESCE(n.published_at, n.created_at) DESC, n.id DESC
            LIMIT :limit OFFSET :offset
            """,
            Map.of("learnerId", learnerId, "tier", learnerTier, "limit", limit, "offset", offset),
            (rs, rowNum) -> mapRow(rs, false));
    }

    @Override
    public long countPublishedForAudience(UUID learnerId, String learnerTier, boolean unreadOnly) {
        String unreadFilter = unreadOnly ? " AND nr.read_at IS NULL " : "";
        Long count = jdbc.queryForObject("""
            SELECT count(*)
            FROM learner_notification n
            LEFT JOIN learner_notification_read nr
              ON nr.notification_id = n.id AND nr.learner_id = :learnerId
            WHERE n.status = 'PUBLISHED'
              AND (n.target_audience IN ('ALL', :tier) OR (n.target_audience = 'SELECTED' AND EXISTS (
                    SELECT 1 FROM learner_notification_recipient r
                    WHERE r.notification_id = n.id AND r.learner_id = :learnerId)))
              AND (n.published_at IS NULL OR n.published_at <= now())
              AND (n.expires_at IS NULL OR n.expires_at > now())
            """ + unreadFilter,
            Map.of("learnerId", learnerId, "tier", learnerTier),
            Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public long countUnreadForAudience(UUID learnerId, String learnerTier) {
        return countPublishedForAudience(learnerId, learnerTier, true);
    }

    @Override
    public boolean markRead(UUID learnerId, String learnerTier, UUID notificationId) {
        Integer visible = jdbc.queryForObject("""
            SELECT count(*)
            FROM learner_notification n
            WHERE n.id = :notificationId
              AND n.status = 'PUBLISHED'
              AND (n.target_audience IN ('ALL', :tier) OR (n.target_audience = 'SELECTED' AND EXISTS (
                    SELECT 1 FROM learner_notification_recipient r
                    WHERE r.notification_id = n.id AND r.learner_id = :learnerId)))
              AND (n.published_at IS NULL OR n.published_at <= now())
              AND (n.expires_at IS NULL OR n.expires_at > now())
            """,
            Map.of("learnerId", learnerId, "tier", learnerTier, "notificationId", notificationId),
            Integer.class);
        if (visible == null || visible == 0) {
            return false;
        }
        jdbc.update("""
            INSERT INTO learner_notification_read (learner_id, notification_id, read_at)
            VALUES (:learnerId, :notificationId, now())
            ON CONFLICT (learner_id, notification_id) DO NOTHING
            """, Map.of("learnerId", learnerId, "notificationId", notificationId));
        return true;
    }

    @Override
    public int markAllRead(UUID learnerId, String learnerTier) {
        return jdbc.update("""
            INSERT INTO learner_notification_read (learner_id, notification_id, read_at)
            SELECT :learnerId, n.id, now()
            FROM learner_notification n
            WHERE n.status = 'PUBLISHED'
              AND (n.target_audience IN ('ALL', :tier) OR (n.target_audience = 'SELECTED' AND EXISTS (
                    SELECT 1 FROM learner_notification_recipient r
                    WHERE r.notification_id = n.id AND r.learner_id = :learnerId)))
              AND (n.published_at IS NULL OR n.published_at <= now())
              AND (n.expires_at IS NULL OR n.expires_at > now())
            ON CONFLICT (learner_id, notification_id) DO NOTHING
            """, Map.of("learnerId", learnerId, "tier", learnerTier));
    }

    @Override
    public boolean recipientsExist(List<UUID> recipientIds) {
        return jdbc.queryForObject("SELECT count(*) FROM notification_learner_directory WHERE id IN (:ids)",
            Map.of("ids", recipientIds), Integer.class) == recipientIds.size();
    }

    @Override
    public List<AdminActivity> listActivity(String category, int offset, int limit) {
        String filter = category == null ? "" : "WHERE a.category = :category ";
        var args = new MapSqlParameterSource("category", category).addValue("offset", offset).addValue("limit", limit);
        return jdbc.query("""
            SELECT a.*, u.email_snapshot
            FROM admin_activity_directory a LEFT JOIN identity_user_directory u ON u.id = a.learner_id
            """ + filter + "ORDER BY a.occurred_at DESC, a.id DESC OFFSET :offset LIMIT :limit", args,
            (rs, n) -> new AdminActivity(rs.getString("id"), rs.getString("category"), rs.getString("action"),
                rs.getObject("learner_id", UUID.class), rs.getString("email_snapshot"), rs.getString("actor"),
                rs.getString("detail"), rs.getObject("amount", Integer.class), toInstant(rs.getTimestamp("occurred_at")),
                toInstant(rs.getTimestamp("scheduled_for"))));
    }

    @Override
    public void audit(LearnerNotification item, String actor, String action) {
        jdbc.update("""
            INSERT INTO learner_notification_audit (notification_id, actor, action, title, scheduled_for)
            VALUES (:id, :actor, :action, :title, :scheduledFor)
            """, new MapSqlParameterSource("id", item.id())
                .addValue("actor", actor).addValue("action", action).addValue("title", item.title())
                .addValue("scheduledFor", "NOTIFICATION_SCHEDULED".equals(action)
                    ? item.publishedAt().atOffset(ZoneOffset.UTC) : null, Types.TIMESTAMP_WITH_TIMEZONE));
    }

    @Override
    public Optional<LearnerNotification> findByIdForUpdate(UUID id) {
        return jdbc.query(SELECT + "WHERE n.id = :id FOR UPDATE", Map.of("id", id),
            (rs, rowNum) -> mapRow(rs, true)).stream().findFirst();
    }

    @Override
    public LearnerNotification insert(LearnerNotification item) {
        jdbc.update("""
            INSERT INTO learner_notification (id, title, body, category, target_audience, status,
                published_at, expires_at, created_by)
            VALUES (:id, :title, :body, :category, :targetAudience, :status,
                :publishedAt, :expiresAt, :createdBy)
            """, params(item));
        replaceRecipients(item);
        return findByIdForUpdate(item.id()).orElseThrow();
    }

    @Override
    public Optional<LearnerNotification> update(LearnerNotification item) {
        int updated = jdbc.update("""
            UPDATE learner_notification
            SET title = :title, body = :body, category = :category, target_audience = :targetAudience,
                status = :status, published_at = :publishedAt, expires_at = :expiresAt, updated_at = now()
            WHERE id = :id
            """, params(item));
        if (updated == 0) return Optional.empty();
        replaceRecipients(item);
        return findByIdForUpdate(item.id());
    }

    private void replaceRecipients(LearnerNotification item) {
        jdbc.update("DELETE FROM learner_notification_recipient WHERE notification_id = :id", Map.of("id", item.id()));
        if (item.recipientIds().isEmpty()) return;
        int inserted = jdbc.update("""
            INSERT INTO learner_notification_recipient (notification_id, learner_id)
            SELECT :id, id FROM notification_learner_directory WHERE id IN (:recipients)
            """, Map.of("id", item.id(), "recipients", item.recipientIds()));
        if (inserted != item.recipientIds().size()) {
            throw new StateConflictException("A selected learner is no longer available");
        }
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
            .addValue("publishedAt", item.publishedAt() == null ? null : item.publishedAt().atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
            .addValue("expiresAt", item.expiresAt() == null ? null : item.expiresAt().atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
            .addValue("createdBy", item.createdBy());
    }

    private static LearnerNotification mapRow(ResultSet rs, boolean includeRecipients) throws SQLException {
        return new LearnerNotification(
            rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("body"), rs.getString("category"),
            rs.getString("target_audience"), rs.getString("status"), toInstant(rs.getTimestamp("published_at")),
            toInstant(rs.getTimestamp("expires_at")), rs.getString("created_by"), toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            includeRecipients ? List.of((UUID[]) rs.getArray("recipients").getArray()) : List.of(),
            includeRecipients ? List.of((String[]) rs.getArray("recipient_labels").getArray()) : List.of(),
            !includeRecipients && rs.getBoolean("is_read"),
            includeRecipients ? null : toInstant(rs.getTimestamp("learner_read_at"))
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
