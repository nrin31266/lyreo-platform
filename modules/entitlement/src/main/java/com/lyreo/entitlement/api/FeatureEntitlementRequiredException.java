package com.lyreo.entitlement.api;

import java.util.Objects;
import org.springframework.modulith.NamedInterface;
import org.springframework.security.access.AccessDeniedException;

/** Access denial raised when a protected capability has no current user grant. */
@NamedInterface(value = "api", propagate = false)
public final class FeatureEntitlementRequiredException extends AccessDeniedException {
    private final FeatureKey featureKey;

    public FeatureEntitlementRequiredException(FeatureKey featureKey) {
        super("Required feature is not available");
        this.featureKey = Objects.requireNonNull(featureKey, "featureKey");
    }

    public FeatureKey featureKey() {
        return featureKey;
    }
}
