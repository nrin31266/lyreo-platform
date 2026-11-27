package com.lyreo.entitlement.application.internal;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.entitlement.application.port.EntitlementAuditRepository;
import com.lyreo.entitlement.application.port.FeatureRepository;
import com.lyreo.entitlement.application.port.QuotaPolicyRepository;
import com.lyreo.entitlement.application.port.UserEntitlementGrantRepository;
import com.lyreo.entitlement.domain.EntitlementAuditEntry;
import com.lyreo.entitlement.domain.EntitlementGrant;
import com.lyreo.entitlement.domain.QuotaPolicy;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Admin reads legacy grants and manages feature quota policy. */
public class EntitlementGrantAdminService {
    private static final String DEFAULT_POLICY_VERSION = "dev-v1";

    private final FeatureRepository features;
    private final UserEntitlementGrantRepository grants;
    private final EntitlementAuditRepository auditLogs;
    private final QuotaPolicyRepository quotaPolicies;
    private final Clock clock;

    public EntitlementGrantAdminService(
        FeatureRepository features,
        UserEntitlementGrantRepository grants,
        EntitlementAuditRepository auditLogs,
        Clock clock
    ) {
        this(features, grants, auditLogs, null, clock);
    }

    public EntitlementGrantAdminService(
        FeatureRepository features,
        UserEntitlementGrantRepository grants,
        EntitlementAuditRepository auditLogs,
        QuotaPolicyRepository quotaPolicies,
        Clock clock
    ) {
        this.features = Objects.requireNonNull(features, "features");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.auditLogs = Objects.requireNonNull(auditLogs, "auditLogs");
        this.quotaPolicies = quotaPolicies;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<FeatureRepository.FeatureDefinition> listFeatures() {
        return features.findAll();
    }

    public List<QuotaPolicy> listQuotas() {
        return quotaPolicies != null ? quotaPolicies.findAll() : List.of();
    }

    @Transactional
    public QuotaPolicy upsertQuota(
        String rawFeatureKey,
        String tier,
        String limitType,
        String periodUnit,
        int maxAmount,
        String policyVersion,
        String actor
    ) {
        if (quotaPolicies == null) {
            throw new IllegalStateException("QuotaPolicyRepository is not configured");
        }
        FeatureKey featureKey = parseFeatureKey(rawFeatureKey);
        if (!features.exists(featureKey)) {
            throw new ResourceNotFoundException("Entitlement feature not found: " + featureKey.value());
        }
        String normalizedTier = tier == null ? "" : tier.trim().toUpperCase(java.util.Locale.ROOT);
        String normalizedLimitType = limitType == null ? "" : limitType.trim().toUpperCase(java.util.Locale.ROOT);
        String normalizedPeriod = periodUnit == null ? "" : periodUnit.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("FREE", "PLUS").contains(normalizedTier)) {
            throw new RequestValidationException("tier must be FREE or PLUS");
        }
        if (!List.of("PERIOD_USAGE", "CAPACITY").contains(normalizedLimitType)) {
            throw new RequestValidationException("limitType must be PERIOD_USAGE or CAPACITY");
        }
        if (!List.of("DAY", "NONE").contains(normalizedPeriod)) {
            throw new RequestValidationException("periodUnit must be DAY or NONE");
        }
        if (maxAmount < 0) {
            throw new RequestValidationException("maxAmount must be >= 0");
        }
        String resolvedPolicyVersion = (policyVersion == null || policyVersion.isBlank())
            ? DEFAULT_POLICY_VERSION
            : policyVersion.trim();
        QuotaPolicy saved = quotaPolicies.upsert(new QuotaPolicy(
            featureKey,
            normalizedTier,
            normalizedLimitType,
            normalizedPeriod,
            maxAmount,
            resolvedPolicyVersion
        ));
        String normalizedActor = (actor == null || actor.isBlank()) ? "admin" : actor.trim();
        auditLogs.record(new EntitlementAuditEntry(
            UUID.randomUUID(),
            null,
            saved.featureKey().value(),
            null,
            "QUOTA_UPDATED",
            saved.policyVersion(),
            Map.of(
                "actor", normalizedActor,
                "tier", saved.tier(),
                "limitType", saved.limitType(),
                "periodUnit", saved.periodUnit(),
                "maxAmount", saved.maxAmount()
            ),
            clock.instant(),
            normalizedActor
        ));
        return saved;
    }

    public List<EntitlementAuditEntry> listAudit(UUID userId, String rawFeatureKey, int limit) {
        FeatureKey featureKey = rawFeatureKey == null || rawFeatureKey.isBlank()
            ? null
            : parseFeatureKey(rawFeatureKey);
        return auditLogs.findRecent(userId, featureKey != null ? featureKey.value() : null, limit);
    }

    public List<EntitlementGrant> listGrants(UUID userId, String rawFeatureKey) {
        FeatureKey featureKey = rawFeatureKey == null || rawFeatureKey.isBlank()
            ? null
            : parseFeatureKey(rawFeatureKey);
        return grants.findGrants(userId, featureKey);
    }

    private static FeatureKey parseFeatureKey(String rawFeatureKey) {
        try {
            return FeatureKey.of(rawFeatureKey);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new RequestValidationException("Invalid featureKey: " + rawFeatureKey, ex);
        }
    }
}
