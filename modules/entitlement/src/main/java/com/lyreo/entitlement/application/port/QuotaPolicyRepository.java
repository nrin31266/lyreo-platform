package com.lyreo.entitlement.application.port;

import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.entitlement.domain.QuotaPolicy;
import java.util.List;
import java.util.Optional;

/** Reads and updates configured per-tier quota and capacity policies. */
public interface QuotaPolicyRepository {
    Optional<QuotaPolicy> findPolicy(FeatureKey featureKey, String tier);

    default List<QuotaPolicy> findAll() {
        return List.of();
    }

    default QuotaPolicy upsert(QuotaPolicy policy) {
        return policy;
    }
}
