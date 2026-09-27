package com.lyreo.entitlement.application.port;

import com.lyreo.entitlement.api.FeatureKey;
import java.time.Instant;
import java.util.UUID;

/** Evaluates persisted user grants at a specific instant. */
public interface UserEntitlementGrantRepository {
    boolean hasActiveGrant(UUID userId, FeatureKey featureKey, Instant asOf);
}
