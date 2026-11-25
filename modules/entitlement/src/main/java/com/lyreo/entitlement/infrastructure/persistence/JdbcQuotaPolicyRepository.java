package com.lyreo.entitlement.infrastructure.persistence;

import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.entitlement.application.port.QuotaPolicyRepository;
import com.lyreo.entitlement.domain.QuotaPolicy;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcQuotaPolicyRepository implements QuotaPolicyRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcQuotaPolicyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<QuotaPolicy> findPolicy(FeatureKey featureKey, String tier) {
        return jdbc.query(
            """
            SELECT feature_key, tier, limit_type, period_unit, max_amount, policy_version
              FROM entitlement_quota_policy
             WHERE feature_key = :featureKey
               AND tier = :tier
            """,
            new MapSqlParameterSource()
                .addValue("featureKey", featureKey.value())
                .addValue("tier", tier),
            (rs, rowNum) -> mapPolicy(rs)
        ).stream().findFirst();
    }

    @Override
    public List<QuotaPolicy> findAll() {
        return jdbc.query(
            """
            SELECT feature_key, tier, limit_type, period_unit, max_amount, policy_version
              FROM entitlement_quota_policy
             ORDER BY feature_key ASC, tier ASC
            """,
            (rs, rowNum) -> mapPolicy(rs)
        );
    }

    @Override
    public QuotaPolicy upsert(QuotaPolicy policy) {
        return jdbc.queryForObject(
            """
            INSERT INTO entitlement_quota_policy(
                feature_key, tier, limit_type, period_unit, max_amount, policy_version, updated_at
            ) VALUES (
                :featureKey, :tier, :limitType, :periodUnit, :maxAmount, :policyVersion, now()
            )
            ON CONFLICT (feature_key, tier) DO UPDATE SET
                limit_type = excluded.limit_type,
                period_unit = excluded.period_unit,
                max_amount = excluded.max_amount,
                policy_version = excluded.policy_version,
                updated_at = now()
            RETURNING feature_key, tier, limit_type, period_unit, max_amount, policy_version
            """,
            new MapSqlParameterSource()
                .addValue("featureKey", policy.featureKey().value())
                .addValue("tier", policy.tier())
                .addValue("limitType", policy.limitType())
                .addValue("periodUnit", policy.periodUnit())
                .addValue("maxAmount", policy.maxAmount())
                .addValue("policyVersion", policy.policyVersion()),
            (rs, rowNum) -> mapPolicy(rs)
        );
    }

    private static QuotaPolicy mapPolicy(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new QuotaPolicy(
            FeatureKey.of(rs.getString("feature_key")),
            rs.getString("tier"),
            rs.getString("limit_type"),
            rs.getString("period_unit"),
            rs.getInt("max_amount"),
            rs.getString("policy_version")
        );
    }
}
