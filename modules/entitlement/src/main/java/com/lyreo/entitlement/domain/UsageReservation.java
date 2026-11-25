package com.lyreo.entitlement.domain;

import com.lyreo.entitlement.api.FeatureKey;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persisted reservation ledger entry keyed idempotently by (userId, featureKey, operationId). */
public record UsageReservation(
    UUID id,
    UUID userId,
    FeatureKey featureKey,
    String operationId,
    String periodKey,
    int amount,
    String status,
    String policyVersion,
    Instant createdAt,
    Instant resolvedAt
) {
    public static final String STATUS_RESERVED = "RESERVED";
    public static final String STATUS_SETTLED = "SETTLED";
    public static final String STATUS_RELEASED = "RELEASED";

    public UsageReservation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(featureKey, "featureKey");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(periodKey, "periodKey");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(createdAt, "createdAt");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
    }
}
