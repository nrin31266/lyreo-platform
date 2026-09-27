package com.lyreo.entitlement.application.internal;

import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.entitlement.api.FeatureEntitlementRequiredException;
import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.entitlement.application.port.FeatureRepository;
import com.lyreo.entitlement.application.port.UserEntitlementGrantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Evaluates runtime grants without coupling consumers to persistence details. */
public final class DefaultEntitlementService implements EntitlementService {
    private final FeatureRepository features;
    private final UserEntitlementGrantRepository grants;
    private final Clock clock;

    public DefaultEntitlementService(
        FeatureRepository features,
        UserEntitlementGrantRepository grants,
        Clock clock
    ) {
        this.features = Objects.requireNonNull(features, "features");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean hasFeature(UUID userId, FeatureKey featureKey) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(featureKey, "featureKey");
        if (!features.isActive(featureKey)) {
            return false;
        }

        Instant now = clock.instant();
        return grants.hasActiveGrant(userId, featureKey, now);
    }

    @Override
    public void requireFeature(UUID userId, FeatureKey featureKey) {
        if (!hasFeature(userId, featureKey)) {
            throw new FeatureEntitlementRequiredException(featureKey);
        }
    }
}
