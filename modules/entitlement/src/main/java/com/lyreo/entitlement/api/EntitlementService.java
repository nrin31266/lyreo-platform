package com.lyreo.entitlement.api;

import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/** Public runtime contract for checking feature access. */
@NamedInterface(value = "api", propagate = false)
public interface EntitlementService {

    /** Returns whether the user currently has an active grant for the feature. */
    boolean hasFeature(UUID userId, FeatureKey featureKey);

    /** Throws an access-denied exception when the user has no current grant. */
    void requireFeature(UUID userId, FeatureKey featureKey);
}
