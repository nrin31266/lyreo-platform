package com.lyreo.entitlement.application.port;

import com.lyreo.entitlement.api.FeatureKey;

/** Reads feature definitions needed by entitlement evaluation. */
public interface FeatureRepository {
    boolean isActive(FeatureKey featureKey);
}
