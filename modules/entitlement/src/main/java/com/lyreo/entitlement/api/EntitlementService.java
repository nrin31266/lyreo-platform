package com.lyreo.entitlement.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/** Public runtime contract for checking feature access, capacity, and period quotas. */
@NamedInterface(value = "api", propagate = false)
public interface EntitlementService {

    /** Returns whether the learner has an active Plus membership. */
    default boolean isPlus(UUID userId) {
        return false;
    }

    /** Returns the learner's current membership tier and Plus expiry timestamp when active. */
    default PlanSummary currentPlan(UUID userId) {
        return new PlanSummary(isPlus(userId) ? "PLUS" : "FREE", null);
    }

    @NamedInterface(value = "api", propagate = false)
    record PlanSummary(String tier, Instant validUntil) {}

    /** Returns whether the feature is active and the learner has Plus. */
    boolean hasFeature(UUID userId, FeatureKey featureKey);

    /** Throws an access-denied exception when Plus access to the feature is unavailable. */
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
