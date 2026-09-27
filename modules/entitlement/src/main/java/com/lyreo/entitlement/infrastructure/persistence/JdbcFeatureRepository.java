package com.lyreo.entitlement.infrastructure.persistence;

import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.entitlement.application.port.FeatureRepository;
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
}
