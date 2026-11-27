package com.lyreo.entitlement.infrastructure.persistence;

import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.entitlement.application.port.UserEntitlementGrantRepository;
import com.lyreo.entitlement.domain.EntitlementGrant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserEntitlementGrantRepository implements UserEntitlementGrantRepository {
    private static final String GRANT_COLUMNS = """
        id, user_id, feature_key, status, valid_from, valid_until,
        source_type, source_reference, created_by, created_at,
        revoked_at, revoked_by, revocation_reason
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcUserEntitlementGrantRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean hasActiveGrant(UUID userId, FeatureKey featureKey, Instant asOf) {
        Boolean granted = jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                  FROM user_entitlement_grant g
                 WHERE g.user_id = :userId
                   AND g.feature_key = :featureKey
                   AND g.status = 'ACTIVE'
                   AND g.revoked_at IS NULL
                   AND g.valid_from <= :asOf
                   AND (g.valid_until IS NULL OR g.valid_until > :asOf)
            )
            """,
            new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("featureKey", featureKey.value())
                .addValue("asOf", Timestamp.from(asOf)),
            Boolean.class
        );
        return Boolean.TRUE.equals(granted);
    }

    @Override
    public Optional<EntitlementGrant> findActiveGrant(UUID userId, FeatureKey featureKey, Instant asOf) {
        return jdbc.query(
            """
            SELECT %s
              FROM user_entitlement_grant g
             WHERE g.user_id = :userId
               AND g.feature_key = :featureKey
               AND g.status = 'ACTIVE'
               AND g.revoked_at IS NULL
               AND g.valid_from <= :asOf
               AND (g.valid_until IS NULL OR g.valid_until > :asOf)
             ORDER BY g.valid_until DESC NULLS FIRST, g.created_at DESC
             LIMIT 1
            """.formatted(GRANT_COLUMNS),
            new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("featureKey", featureKey.value())
                .addValue("asOf", Timestamp.from(asOf)),
            JdbcUserEntitlementGrantRepository::mapGrant
        ).stream().findFirst();
    }

    @Override
    public List<EntitlementGrant> findGrants(UUID userId, FeatureKey featureKey) {
        List<String> conditions = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (userId != null) {
            conditions.add("g.user_id = :userId");
            params.addValue("userId", userId);
        }
        if (featureKey != null) {
            conditions.add("g.feature_key = :featureKey");
            params.addValue("featureKey", featureKey.value());
        }
        String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
        return jdbc.query(
            """
            SELECT %s
              FROM user_entitlement_grant g
             %s
             ORDER BY g.created_at DESC, g.id DESC
            """.formatted(GRANT_COLUMNS, where),
            params,
            JdbcUserEntitlementGrantRepository::mapGrant
        );
    }

    @Override
    public Optional<EntitlementGrant> findById(UUID grantId) {
        return jdbc.query(
            "SELECT " + GRANT_COLUMNS + " FROM user_entitlement_grant WHERE id = :grantId",
            new MapSqlParameterSource("grantId", grantId),
            JdbcUserEntitlementGrantRepository::mapGrant
        ).stream().findFirst();
    }

    @Override
    public EntitlementGrant createGrant(EntitlementGrant grant) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("id", grant.id())
            .addValue("userId", grant.userId())
            .addValue("featureKey", grant.featureKey())
            .addValue("status", grant.status())
            .addValue("validFrom", Timestamp.from(grant.validFrom()))
            .addValue(
                "validUntil",
                grant.validUntil() == null ? null : Timestamp.from(grant.validUntil()),
                Types.TIMESTAMP
            )
            .addValue("sourceType", grant.sourceType())
            .addValue("sourceReference", grant.sourceReference(), Types.VARCHAR)
            .addValue("createdBy", grant.createdBy());

        return jdbc.queryForObject(
            """
            INSERT INTO user_entitlement_grant (
                id, user_id, feature_key, status, valid_from, valid_until,
                source_type, source_reference, created_by
            ) VALUES (
                :id, :userId, :featureKey, :status, :validFrom, :validUntil,
                :sourceType, :sourceReference, :createdBy
            )
            RETURNING %s
            """.formatted(GRANT_COLUMNS),
            params,
            JdbcUserEntitlementGrantRepository::mapGrant
        );
    }

    @Override
    public Optional<EntitlementGrant> revokeGrant(
        UUID grantId,
        String revokedBy,
        String reason,
        Instant revokedAt
    ) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("grantId", grantId)
            .addValue("revokedBy", revokedBy)
            .addValue("reason", reason, Types.VARCHAR)
            .addValue("revokedAt", Timestamp.from(revokedAt));

        return jdbc.query(
            """
            UPDATE user_entitlement_grant
               SET status = 'REVOKED',
                   revoked_at = :revokedAt,
                   revoked_by = :revokedBy,
                   revocation_reason = :reason
             WHERE id = :grantId
               AND status = 'ACTIVE'
            RETURNING %s
            """.formatted(GRANT_COLUMNS),
            params,
            JdbcUserEntitlementGrantRepository::mapGrant
        ).stream().findFirst();
    }

    private static EntitlementGrant mapGrant(ResultSet rs, int rowNum) throws SQLException {
        return new EntitlementGrant(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("feature_key"),
            rs.getString("status"),
            toInstant(rs.getTimestamp("valid_from")),
            toInstant(rs.getTimestamp("valid_until")),
            rs.getString("source_type"),
            rs.getString("source_reference"),
            rs.getString("created_by"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("revoked_at")),
            rs.getString("revoked_by"),
            rs.getString("revocation_reason")
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
