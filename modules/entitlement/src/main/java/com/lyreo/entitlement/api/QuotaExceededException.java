package com.lyreo.entitlement.api;

import java.time.Instant;
import java.util.Objects;
import org.springframework.modulith.NamedInterface;

/** Access denial raised when a protected capability reaches its period quota or capacity limit. */
@NamedInterface(value = "api", propagate = false)
public class QuotaExceededException extends FeatureEntitlementRequiredException {
    private final String limitType;
    private final int limit;
    private final int remaining;
    private final Instant resetAt;

    public QuotaExceededException(
        FeatureKey featureKey,
        String limitType,
        int limit,
        int remaining,
        Instant resetAt
    ) {
        super(featureKey);
        this.limitType = Objects.requireNonNull(limitType, "limitType");
        this.limit = limit;
        this.remaining = Math.max(0, remaining);
        this.resetAt = resetAt;
    }

    public String limitType() {
        return limitType;
    }

    public int limit() {
        return limit;
    }

    public int remaining() {
        return remaining;
    }

    public Instant resetAt() {
        return resetAt;
    }
}
