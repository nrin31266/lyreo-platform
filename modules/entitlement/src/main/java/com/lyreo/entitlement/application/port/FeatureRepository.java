package com.lyreo.entitlement.application.port;

import com.lyreo.entitlement.api.FeatureKey;
import java.util.List;

/** Reads feature definitions needed by entitlement evaluation. */
public interface FeatureRepository {
    boolean isActive(FeatureKey featureKey);

    default boolean exists(FeatureKey featureKey) {
        return isActive(featureKey);
    }

    default List<FeatureKey> findAllActiveKeys() {
        return List.of();
    }

    default List<FeatureDefinition> findAll() {
        return List.of();
    }

    record FeatureDefinition(String featureKey, String displayName, String description, String status) {}
}
