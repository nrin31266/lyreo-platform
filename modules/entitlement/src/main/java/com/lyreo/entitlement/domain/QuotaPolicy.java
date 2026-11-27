package com.lyreo.entitlement.domain;

import com.lyreo.entitlement.api.FeatureKey;
import java.util.Objects;

/** Configured tier quota or capacity rule for a feature key. */
public record QuotaPolicy(
    FeatureKey featureKey,
    String tier,
    String limitType,
    String periodUnit,
    int maxAmount,
    String policyVersion
) {
    public QuotaPolicy {
        Objects.requireNonNull(featureKey, "featureKey");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(limitType, "limitType");
        Objects.requireNonNull(periodUnit, "periodUnit");
        Objects.requireNonNull(policyVersion, "policyVersion");
        if (maxAmount < 0) {
            throw new IllegalArgumentException("maxAmount must be >= 0");
        }
    }
}
