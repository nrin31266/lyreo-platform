package com.lyreo.platform.jobs.infrastructure;

import com.lyreo.platform.jobs.application.BackgroundJobRepository;
import com.lyreo.platform.jobs.application.CancellationResult;
import com.lyreo.platform.jobs.domain.BackgroundJob;
import com.lyreo.platform.jobs.domain.BackgroundJobStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcBackgroundJobRepository implements BackgroundJobRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcBackgroundJobRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID enqueue(String type, String ownerModule, UUID ownerReferenceId, int priority, int maxAttempts, String snapshot) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO background_job(
              id, job_type, owner_module, owner_reference_id, status, priority,
              progress_percent, attempt_count, max_attempts, config_snapshot_json, created_at
            ) VALUES (
              :id, :type, :ownerModule, :ownerReferenceId, 'QUEUED', :priority,
              0, 0, :maxAttempts, CAST(:snapshot AS jsonb), now()
            )
            """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("type", type)
                .addValue("ownerModule", ownerModule)
                .addValue("ownerReferenceId", ownerReferenceId)
                .addValue("priority", priority)
                .addValue("maxAttempts", maxAttempts)
                .addValue("snapshot", snapshot == null ? "{}" : snapshot));
        return id;
    }

    @Override
    public List<BackgroundJob> claim(String workerId, int limit, Duration lease) {
        return jdbc.query("""
            WITH candidates AS (
                SELECT id
                  FROM background_job
                 WHERE status = 'QUEUED'
                    OR (status = 'RETRY_WAIT' AND next_retry_at <= now())
                 ORDER BY priority DESC, created_at
                 FOR UPDATE SKIP LOCKED
                 LIMIT :limit
            )
            UPDATE background_job j
               SET status = 'RUNNING',
                   attempt_count = attempt_count + 1,
                   lease_owner = :workerId,
                   lease_until = now() + (:leaseSeconds * interval '1 second'),
                   heartbeat_at = now(),
                   started_at = COALESCE(started_at, now())
              FROM candidates c
             WHERE j.id = c.id
            RETURNING j.*
            """, Map.of(
                "limit", limit,
                "workerId", workerId,
                "leaseSeconds", lease.toSeconds()
            ), this::map);
    }

    @Override
    public Optional<BackgroundJob> findById(UUID id) {
        return jdbc.query(
            "SELECT * FROM background_job WHERE id = :id",
            Map.of("id", id), this::map
        ).stream().findFirst();
    }

    @Override
    public CancellationResult requestCancellation(UUID id) {
        String result = jdbc.queryForObject("""
            WITH updated AS (
                UPDATE background_job
                   SET status = CASE
                           WHEN status IN ('QUEUED','RETRY_WAIT') THEN 'CANCELLED'
                           ELSE 'CANCEL_REQUESTED'
                       END,
                       cancel_requested_at = now(),
                       finished_at = CASE
                           WHEN status IN ('QUEUED','RETRY_WAIT') THEN now()
                           ELSE finished_at
                       END
                 WHERE id = :id
                   AND status IN ('QUEUED','RUNNING','RETRY_WAIT')
                RETURNING id
            )
            SELECT CASE
                WHEN EXISTS (SELECT 1 FROM updated) THEN 'ACCEPTED'
                WHEN EXISTS (SELECT 1 FROM background_job WHERE id = :id) THEN 'NOT_CANCELLABLE'
                ELSE 'NOT_FOUND'
            END AS result
            """, Map.of("id", id), String.class);

        return CancellationResult.valueOf(result != null ? result : "NOT_FOUND");
    }

    @Override
    public boolean isCancellationRequested(UUID id) {
        Boolean value = jdbc.queryForObject("""
            SELECT (status IN ('CANCEL_REQUESTED','CANCELLED') OR cancel_requested_at IS NOT NULL)
              FROM background_job
             WHERE id = :id
            """, Map.of("id", id), Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    @Override
    public boolean heartbeat(UUID id, String workerId, Duration lease) {
        return jdbc.update("""
            UPDATE background_job
               SET heartbeat_at = now(),
                   lease_until = now() + (:leaseSeconds * interval '1 second')
             WHERE id = :id
               AND lease_owner = :workerId
               AND status IN ('RUNNING','CANCEL_REQUESTED')
            """, Map.of("id", id, "workerId", workerId, "leaseSeconds", lease.toSeconds())) == 1;
    }

    @Override
    public boolean updateProgress(UUID id, String workerId, String step, int percent) {
        int bounded = Math.max(0, Math.min(100, percent));
        return jdbc.update("""
            UPDATE background_job
               SET current_step = :step, progress_percent = :percent
             WHERE id = :id
               AND lease_owner = :workerId
               AND status IN ('RUNNING','CANCEL_REQUESTED')
            """, Map.of(
                "id", id,
                "workerId", workerId,
                "step", step == null ? "" : step,
                "percent", bounded
            )) == 1;
    }

    @Override
    public boolean markSucceeded(UUID id, String workerId) {
        return finish(id, workerId, "SUCCEEDED", null, null);
    }

    @Override
    public boolean markCancelled(UUID id, String workerId) {
        return finish(id, workerId, "CANCELLED", null, null);
    }

    @Override
    public boolean markFailed(UUID id, String workerId, String code, String message) {
        return finish(id, workerId, "FAILED", code, message);
    }

    private boolean finish(UUID id, String workerId, String status, String code, String message) {
        return jdbc.update("""
            UPDATE background_job
               SET status = :status,
                   progress_percent = CASE WHEN :status = 'SUCCEEDED' THEN 100 ELSE progress_percent END,
                   error_code = :code,
                   error_message = :message,
                   lease_owner = NULL,
                   lease_until = NULL,
                   finished_at = now()
             WHERE id = :id
               AND lease_owner = :workerId
               AND status IN ('RUNNING','CANCEL_REQUESTED')
            """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("workerId", workerId)
                .addValue("status", status)
                .addValue("code", code)
                .addValue("message", message)) == 1;
    }

    @Override
    public boolean scheduleRetry(UUID id, String workerId, Instant nextRetryAt, String code, String message) {
        return jdbc.update("""
            UPDATE background_job
               SET status = 'RETRY_WAIT',
                   next_retry_at = :nextRetryAt,
                   error_code = :code,
                   error_message = :message,
                   lease_owner = NULL,
                   lease_until = NULL
             WHERE id = :id
               AND lease_owner = :workerId
               AND status = 'RUNNING'
            """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("workerId", workerId)
                .addValue("nextRetryAt", nextRetryAt != null ? Timestamp.from(nextRetryAt) : null)
                .addValue("code", code)
                .addValue("message", message)) == 1;
    }

    @Override
    public List<BackgroundJob> recoverExpiredLeases(Instant now) {
        return jdbc.query("""
            UPDATE background_job
               SET status = CASE
                    WHEN cancel_requested_at IS NOT NULL THEN 'CANCELLED'
                    ELSE 'QUEUED'
                   END,
                   lease_owner = NULL,
                   lease_until = NULL,
                   finished_at = CASE
                    WHEN cancel_requested_at IS NOT NULL THEN now()
                    ELSE finished_at
                   END,
                   error_code = 'LEASE_EXPIRED',
                   error_message = 'Worker lease expired before job completion'
             WHERE status IN ('RUNNING','CANCEL_REQUESTED')
               AND lease_until < :now
            RETURNING *
            """, Map.of("now", Timestamp.from(now)), this::map);
    }

    private BackgroundJob map(ResultSet rs, int rowNum) throws SQLException {
        return new BackgroundJob(
            rs.getObject("id", UUID.class),
            rs.getString("job_type"),
            rs.getString("owner_module"),
            rs.getObject("owner_reference_id", UUID.class),
            BackgroundJobStatus.valueOf(rs.getString("status")),
            rs.getInt("priority"),
            rs.getString("current_step"),
            rs.getInt("progress_percent"),
            rs.getInt("attempt_count"),
            rs.getInt("max_attempts"),
            instant(rs, "next_retry_at"),
            rs.getString("lease_owner"),
            instant(rs, "lease_until"),
            instant(rs, "cancel_requested_at"),
            rs.getString("error_message")
        );
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        var timestamp = rs.getTimestamp(name);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
