package com.lyreo.entitlement.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Structured entitlement decision and grant audit record. */
public record EntitlementAuditEntry(
    UUID id,
    UUID userId,
    String featureKey,
    String operationId,
    String decision,
    String policyVersion,
    Map<String, Object> detail,
    Instant occurredAt
) {
    public EntitlementAuditEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(featureKey, "featureKey");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(policyVersion, "policyVersion");
        detail = detail == null ? Map.of() : Map.copyOf(detail);
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
