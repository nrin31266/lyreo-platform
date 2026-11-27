package com.lyreo.entitlement.infrastructure.persistence;

import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.entitlement.application.port.UsageQuotaRepository;
import com.lyreo.entitlement.domain.UsageCounter;
import com.lyreo.entitlement.domain.UsageReservation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUsageQuotaRepository implements UsageQuotaRepository {
    private static final String COUNTER_COLUMNS = """
        user_id, feature_key, period_key, used_count, reserved_count, reset_at
        """;

    private static final String RESERVATION_COLUMNS = """
        id, user_id, feature_key, operation_id, period_key, amount,
        status, policy_version, created_at, resolved_at
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcUsageQuotaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockOperation(UUID userId, FeatureKey featureKey, String operationId) {
        jdbc.queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
            new MapSqlParameterSource("lockKey", userId + ":" + featureKey.value() + ":" + operationId),
            Object.class
        );
    }

    @Override
    public Optional<UsageCounter> findCounter(UUID userId, FeatureKey featureKey, String periodKey) {
        return jdbc.query(
            """
            SELECT %s
              FROM entitlement_usage_counter
             WHERE user_id = :userId
               AND feature_key = :featureKey
               AND period_key = :periodKey
            """.formatted(COUNTER_COLUMNS),
            counterKeyParams(userId, featureKey, periodKey),
            JdbcUsageQuotaRepository::mapCounter
        ).stream().findFirst();
    }

    @Override
    public Optional<UsageReservation> findReservationByOperation(
        UUID userId,
        FeatureKey featureKey,
        String operationId
    ) {
        return jdbc.query(
            """
            SELECT %s
              FROM entitlement_usage_reservation
             WHERE user_id = :userId
               AND feature_key = :featureKey
               AND operation_id = :operationId
            """.formatted(RESERVATION_COLUMNS),
            new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("featureKey", featureKey.value())
                .addValue("operationId", operationId),
            JdbcUsageQuotaRepository::mapReservation
        ).stream().findFirst();
    }

    @Override
    public Optional<UsageReservation> findReservationByIdForUpdate(UUID reservationId) {
        return jdbc.query(
            """
            SELECT %s
              FROM entitlement_usage_reservation
             WHERE id = :reservationId
               FOR UPDATE
            """.formatted(RESERVATION_COLUMNS),
            new MapSqlParameterSource("reservationId", reservationId),
            JdbcUsageQuotaRepository::mapReservation
        ).stream().findFirst();
    }

    @Override
    public void ensureCounterExists(UUID userId, FeatureKey featureKey, String periodKey, Instant resetAt) {
        jdbc.update(
            """
            INSERT INTO entitlement_usage_counter (
                user_id, feature_key, period_key, used_count, reserved_count, reset_at
            ) VALUES (
                :userId, :featureKey, :periodKey, 0, 0, :resetAt
            )
            ON CONFLICT (user_id, feature_key, period_key) DO NOTHING
            """,
            counterKeyParams(userId, featureKey, periodKey)
                .addValue("resetAt", Timestamp.from(resetAt))
        );
    }

    @Override
    public Optional<UsageCounter> tryReserveCounterUnit(
        UUID userId,
        FeatureKey featureKey,
        String periodKey,
        int maxAmount
    ) {
        return jdbc.query(
            """
            UPDATE entitlement_usage_counter
               SET reserved_count = reserved_count + 1,
                   updated_at = now()
             WHERE user_id = :userId
               AND feature_key = :featureKey
               AND period_key = :periodKey
               AND (used_count + reserved_count) < :maxAmount
            RETURNING %s
            """.formatted(COUNTER_COLUMNS),
            counterKeyParams(userId, featureKey, periodKey)
                .addValue("maxAmount", maxAmount),
            JdbcUsageQuotaRepository::mapCounter
        ).stream().findFirst();
    }

    @Override
    public Optional<UsageCounter> tryConsumeCounterUnit(
        UUID userId,
        FeatureKey featureKey,
        String periodKey,
        int maxAmount
    ) {
        return jdbc.query(
            """
            UPDATE entitlement_usage_counter
               SET used_count = used_count + 1,
                   updated_at = now()
             WHERE user_id = :userId
               AND feature_key = :featureKey
               AND period_key = :periodKey
               AND (used_count + reserved_count) < :maxAmount
            RETURNING %s
            """.formatted(COUNTER_COLUMNS),
            counterKeyParams(userId, featureKey, periodKey)
                .addValue("maxAmount", maxAmount),
            JdbcUsageQuotaRepository::mapCounter
        ).stream().findFirst();
    }

    @Override
    public void insertReservation(UsageReservation reservation) {
        jdbc.update(
            """
            INSERT INTO entitlement_usage_reservation (
                id, user_id, feature_key, operation_id, period_key,
                amount, status, policy_version, created_at, resolved_at
            ) VALUES (
                :id, :userId, :featureKey, :operationId, :periodKey,
                :amount, :status, :policyVersion, :createdAt, :resolvedAt
            )
            """,
            new MapSqlParameterSource()
                .addValue("id", reservation.id())
                .addValue("userId", reservation.userId())
                .addValue("featureKey", reservation.featureKey().value())
                .addValue("operationId", reservation.operationId())
                .addValue("periodKey", reservation.periodKey())
                .addValue("amount", reservation.amount())
                .addValue("status", reservation.status())
                .addValue("policyVersion", reservation.policyVersion())
                .addValue("createdAt", Timestamp.from(reservation.createdAt()))
                .addValue(
                    "resolvedAt",
                    reservation.resolvedAt() == null ? null : Timestamp.from(reservation.resolvedAt()),
                    Types.TIMESTAMP
                )
        );
    }

    @Override
    public boolean markReservationSettled(UUID reservationId, Instant resolvedAt) {
        int rows = jdbc.update(
            """
            UPDATE entitlement_usage_reservation
               SET status = 'SETTLED',
                   resolved_at = :resolvedAt
             WHERE id = :reservationId
               AND status = 'RESERVED'
            """,
            new MapSqlParameterSource()
                .addValue("reservationId", reservationId)
                .addValue("resolvedAt", Timestamp.from(resolvedAt))
        );
        return rows > 0;
    }

    @Override
    public void settleCounterUnit(UUID userId, FeatureKey featureKey, String periodKey, int amount) {
        jdbc.update(
            """
            UPDATE entitlement_usage_counter
               SET reserved_count = GREATEST(0, reserved_count - :amount),
                   used_count = used_count + :amount,
                   updated_at = now()
             WHERE user_id = :userId
               AND feature_key = :featureKey
               AND period_key = :periodKey
            """,
            counterKeyParams(userId, featureKey, periodKey)
                .addValue("amount", amount)
        );
    }

    @Override
    public boolean markReservationReleased(UUID reservationId, Instant resolvedAt) {
        int rows = jdbc.update(
            """
            UPDATE entitlement_usage_reservation
               SET status = 'RELEASED',
                   resolved_at = :resolvedAt
             WHERE id = :reservationId
               AND status = 'RESERVED'
            """,
            new MapSqlParameterSource()
                .addValue("reservationId", reservationId)
                .addValue("resolvedAt", Timestamp.from(resolvedAt))
        );
        return rows > 0;
    }

    @Override
    public void releaseCounterUnit(UUID userId, FeatureKey featureKey, String periodKey, int amount) {
        jdbc.update(
            """
            UPDATE entitlement_usage_counter
               SET reserved_count = GREATEST(0, reserved_count - :amount),
                   updated_at = now()
             WHERE user_id = :userId
               AND feature_key = :featureKey
               AND period_key = :periodKey
            """,
            counterKeyParams(userId, featureKey, periodKey)
                .addValue("amount", amount)
        );
    }

    private static MapSqlParameterSource counterKeyParams(UUID userId, FeatureKey featureKey, String periodKey) {
        return new MapSqlParameterSource()
            .addValue("userId", userId)
            .addValue("featureKey", featureKey.value())
            .addValue("periodKey", periodKey);
    }

    private static UsageCounter mapCounter(ResultSet rs, int rowNum) throws SQLException {
        return new UsageCounter(
            rs.getObject("user_id", UUID.class),
            FeatureKey.of(rs.getString("feature_key")),
            rs.getString("period_key"),
            rs.getInt("used_count"),
            rs.getInt("reserved_count"),
            rs.getTimestamp("reset_at").toInstant()
        );
    }

    private static UsageReservation mapReservation(ResultSet rs, int rowNum) throws SQLException {
        Timestamp resolved = rs.getTimestamp("resolved_at");
        return new UsageReservation(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            FeatureKey.of(rs.getString("feature_key")),
            rs.getString("operation_id"),
            rs.getString("period_key"),
            rs.getInt("amount"),
            rs.getString("status"),
            rs.getString("policy_version"),
            rs.getTimestamp("created_at").toInstant(),
            resolved == null ? null : resolved.toInstant()
        );
    }
}
