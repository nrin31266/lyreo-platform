package com.lyreo.entitlement.infrastructure.persistence;

import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.entitlement.application.port.FeatureRepository;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFeatureRepository implements FeatureRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcFeatureRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isActive(FeatureKey featureKey) {
        Boolean active = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM entitlement_feature WHERE feature_key = :featureKey AND status = 'ACTIVE')",
            Map.of("featureKey", featureKey.value()),
            Boolean.class
        );
        return Boolean.TRUE.equals(active);
    }

    @Override
    public boolean exists(FeatureKey featureKey) {
        Boolean found = jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM entitlement_feature WHERE feature_key = :featureKey)",
            Map.of("featureKey", featureKey.value()),
            Boolean.class
        );
        return Boolean.TRUE.equals(found);
    }

    @Override
    public List<FeatureKey> findAllActiveKeys() {
        return jdbc.query(
            "SELECT feature_key FROM entitlement_feature WHERE status = 'ACTIVE' ORDER BY feature_key",
            (rs, rowNum) -> FeatureKey.of(rs.getString("feature_key"))
        );
    }

    @Override
    public List<FeatureDefinition> findAll() {
        return jdbc.query(
            "SELECT feature_key, display_name, description, status FROM entitlement_feature ORDER BY feature_key",
            (rs, rowNum) -> new FeatureDefinition(
                rs.getString("feature_key"),
                rs.getString("display_name"),
                rs.getString("description"),
                rs.getString("status")
            )
        );
    }
}
