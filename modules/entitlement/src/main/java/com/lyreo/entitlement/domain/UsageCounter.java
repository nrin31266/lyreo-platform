package com.lyreo.entitlement.domain;

import com.lyreo.entitlement.api.FeatureKey;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Per-learner periodic usage and active reservation counters. */
public record UsageCounter(
    UUID userId,
    FeatureKey featureKey,
    String periodKey,
    int usedCount,
    int reservedCount,
    Instant resetAt
) {
    public UsageCounter {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(featureKey, "featureKey");
        Objects.requireNonNull(periodKey, "periodKey");
        Objects.requireNonNull(resetAt, "resetAt");
    }

    public int consumedAndReserved() {
        return usedCount + reservedCount;
    }
}
