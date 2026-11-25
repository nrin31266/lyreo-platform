package com.lyreo.entitlement.application.port;

import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.entitlement.domain.EntitlementGrant;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Evaluates and manages persisted user entitlement grants. */
public interface UserEntitlementGrantRepository {
    boolean hasActiveGrant(UUID userId, FeatureKey featureKey, Instant asOf);

    default Optional<EntitlementGrant> findActiveGrant(UUID userId, FeatureKey featureKey, Instant asOf) {
        return Optional.empty();
    }

    default List<EntitlementGrant> findGrants(UUID userId, FeatureKey featureKey) {
        return List.of();
    }

    default Optional<EntitlementGrant> findById(UUID grantId) {
        return Optional.empty();
    }

    default EntitlementGrant createGrant(EntitlementGrant grant) {
        throw new UnsupportedOperationException("Grant creation is not configured");
    }

    default Optional<EntitlementGrant> revokeGrant(
        UUID grantId,
        String revokedBy,
        String reason,
        Instant revokedAt
    ) {
        throw new UnsupportedOperationException("Grant revocation is not configured");
    }
}
