package com.lyreo.entitlement.application.port;

import com.lyreo.entitlement.domain.EntitlementAuditEntry;
import java.util.List;
import java.util.UUID;

/** Records and queries entitlement decisions, quota events, and grant administration actions. */
public interface EntitlementAuditRepository {
    void record(EntitlementAuditEntry entry);

    default List<EntitlementAuditEntry> findRecent(UUID userId, String featureKey, int limit) {
        return List.of();
    }

    static EntitlementAuditRepository noop() {
        return entry -> {
        };
    }
}
