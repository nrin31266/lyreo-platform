package com.lyreo.entitlement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** User entitlement grant record covering manual and subscription-backed access windows. */
public record EntitlementGrant(
    UUID id,
    UUID userId,
    String featureKey,
    String status,
    Instant validFrom,
    Instant validUntil,
    String sourceType,
    String sourceReference,
    String createdBy,
    Instant createdAt,
    Instant revokedAt,
    String revokedBy,
    String revocationReason
) {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";

    public EntitlementGrant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(featureKey, "featureKey");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(createdBy, "createdBy");
    }

    public boolean isActiveAt(Instant asOf) {
        return STATUS_ACTIVE.equals(status)
            && revokedAt == null
            && !validFrom.isAfter(asOf)
            && (validUntil == null || validUntil.isAfter(asOf));
    }
}
