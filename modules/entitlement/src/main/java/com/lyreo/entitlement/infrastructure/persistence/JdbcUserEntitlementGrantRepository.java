package com.lyreo.entitlement.infrastructure.persistence;

import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.entitlement.application.port.UserEntitlementGrantRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcUserEntitlementGrantRepository implements UserEntitlementGrantRepository {
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
}
