package com.lyreo.entitlement.api;

import java.util.List;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/** Public runtime contract for checking feature access, capacity, and period quotas. */
@NamedInterface(value = "api", propagate = false)
public interface EntitlementService {

    /** Returns whether the user currently has an active grant for the feature. */
    boolean hasFeature(UUID userId, FeatureKey featureKey);

    /** Throws an access-denied exception when the user has no current grant. */
    void requireFeature(UUID userId, FeatureKey featureKey);

    /** Evaluates current access, tier, quota, and lock reason without throwing. */
    default FeatureAccess evaluateFeature(UUID userId, FeatureKey featureKey) {
        boolean granted = hasFeature(userId, featureKey);
        return new FeatureAccess(
            featureKey.value(),
            granted,
            granted ? "PLUS" : "FREE",
            "GRANT",
            null,
            null,
            null,
            null,
            granted ? null : "FEATURE_GRANT_REQUIRED",
            "dev-v1"
        );
    }

    /** Lists all active feature access summaries for the learner. */
    default List<FeatureAccess> listLearnerEntitlements(UUID userId) {
        return List.of();
    }

    /** Enforces current capacity limits (for example, saved vocabulary card count). */
    default void requireCapacity(UUID userId, FeatureKey featureKey, int currentCount, String operationId) {
    }

    /** Atomically reserves one periodic usage unit for an operation ID. */
    default QuotaReservation reserveQuota(UUID userId, FeatureKey featureKey, String operationId) {
        return new QuotaReservation(
            UUID.randomUUID(),
            featureKey.value(),
            operationId,
            "RESERVED",
            0,
            0,
            null,
            "dev-v1"
        );
    }

    /** Marks a previously reserved usage unit as settled after external work succeeds. */
    default void settleQuota(UUID reservationId) {
    }

    /** Releases a previously reserved usage unit when external work fails or is cancelled. */
    default void releaseQuota(UUID reservationId, String reason) {
    }

    /** Atomically consumes one periodic usage unit synchronously. */
    default QuotaDecision consumeQuota(UUID userId, FeatureKey featureKey, String operationId) {
        return new QuotaDecision(featureKey.value(), operationId, true, 0, 0, null, "dev-v1");
    }
}
