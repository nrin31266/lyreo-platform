package com.lyreo.entitlement.application.internal;

import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.entitlement.api.FeatureAccess;
import com.lyreo.entitlement.api.FeatureEntitlementRequiredException;
import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.entitlement.api.QuotaDecision;
import com.lyreo.entitlement.api.QuotaExceededException;
import com.lyreo.entitlement.api.QuotaReservation;
import com.lyreo.entitlement.application.port.EntitlementAuditRepository;
import com.lyreo.entitlement.application.port.FeatureRepository;
import com.lyreo.entitlement.application.port.QuotaPolicyRepository;
import com.lyreo.entitlement.application.port.UsageQuotaRepository;
import com.lyreo.entitlement.application.port.UserEntitlementGrantRepository;
import com.lyreo.entitlement.domain.EntitlementAuditEntry;
import com.lyreo.entitlement.domain.QuotaPolicy;
import com.lyreo.entitlement.domain.UsageCounter;
import com.lyreo.entitlement.domain.UsageReservation;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Evaluates runtime grants, capacity limits, periodic quotas, and reservations. */
public class DefaultEntitlementService implements EntitlementService {
    private static final String TIER_FREE = "FREE";
    private static final String TIER_PLUS = "PLUS";
    private static final String LIMIT_TYPE_PERIOD_USAGE = "PERIOD_USAGE";
    private static final String LIMIT_TYPE_CAPACITY = "CAPACITY";
    private static final String LIMIT_TYPE_GRANT = "GRANT";
    private static final String DEFAULT_POLICY_VERSION = "dev-v1";

    private final FeatureRepository features;
    private final UserEntitlementGrantRepository grants;
    private final QuotaPolicyRepository quotaPolicies;
    private final UsageQuotaRepository usageQuotas;
    private final EntitlementAuditRepository auditLogs;
    private final Clock clock;

    public DefaultEntitlementService(
        FeatureRepository features,
        UserEntitlementGrantRepository grants,
        Clock clock
    ) {
        this(
            features,
            grants,
            (featureKey, tier) -> Optional.empty(),
            UsageQuotaRepository.noop(),
            EntitlementAuditRepository.noop(),
            clock
        );
    }

    public DefaultEntitlementService(
        FeatureRepository features,
        UserEntitlementGrantRepository grants,
        QuotaPolicyRepository quotaPolicies,
        UsageQuotaRepository usageQuotas,
        EntitlementAuditRepository auditLogs,
        Clock clock
    ) {
        this.features = Objects.requireNonNull(features, "features");
        this.grants = Objects.requireNonNull(grants, "grants");
        this.quotaPolicies = Objects.requireNonNull(quotaPolicies, "quotaPolicies");
        this.usageQuotas = Objects.requireNonNull(usageQuotas, "usageQuotas");
        this.auditLogs = Objects.requireNonNull(auditLogs, "auditLogs");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean hasFeature(UUID userId, FeatureKey featureKey) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(featureKey, "featureKey");
        if (!features.isActive(featureKey)) {
            return false;
        }

        Instant now = clock.instant();
        return grants.hasActiveGrant(userId, featureKey, now);
    }

    @Override
    public void requireFeature(UUID userId, FeatureKey featureKey) {
        if (!hasFeature(userId, featureKey)) {
            throw new FeatureEntitlementRequiredException(featureKey);
        }
    }

    @Override
    public FeatureAccess evaluateFeature(UUID userId, FeatureKey featureKey) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(featureKey, "featureKey");

        if (!features.isActive(featureKey)) {
            return new FeatureAccess(
                featureKey.value(),
                false,
                TIER_FREE,
                LIMIT_TYPE_GRANT,
                null,
                null,
                null,
                null,
                "FEATURE_UNAVAILABLE",
                DEFAULT_POLICY_VERSION
            );
        }

        Instant now = clock.instant();
        boolean hasGrant = grants.hasActiveGrant(userId, featureKey, now);
        String tier = hasGrant ? TIER_PLUS : TIER_FREE;

        Optional<QuotaPolicy> policyOpt = quotaPolicies.findPolicy(featureKey, tier);
        if (policyOpt.isEmpty()) {
            return new FeatureAccess(
                featureKey.value(),
                hasGrant,
                tier,
                LIMIT_TYPE_GRANT,
                null,
                null,
                null,
                null,
                hasGrant ? null : "FEATURE_GRANT_REQUIRED",
                DEFAULT_POLICY_VERSION
            );
        }

        QuotaPolicy policy = policyOpt.get();
        if (LIMIT_TYPE_PERIOD_USAGE.equals(policy.limitType())) {
            UtcDayWindow window = currentUtcDayWindow(now);
            int used = usageQuotas.findCounter(userId, featureKey, window.periodKey())
                .map(UsageCounter::consumedAndReserved)
                .orElse(0);
            int limit = policy.maxAmount();
            int remaining = Math.max(0, limit - used);
            boolean granted = remaining > 0;
            return new FeatureAccess(
                featureKey.value(),
                granted,
                tier,
                LIMIT_TYPE_PERIOD_USAGE,
                limit,
                used,
                remaining,
                window.resetAt(),
                remaining <= 0 ? "QUOTA_EXCEEDED" : null,
                policy.policyVersion()
            );
        }

        return new FeatureAccess(
            featureKey.value(),
            true,
            tier,
            policy.limitType(),
            policy.maxAmount(),
            null,
            null,
            null,
            null,
            policy.policyVersion()
        );
    }

    @Override
    public List<FeatureAccess> listLearnerEntitlements(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        return features.findAllActiveKeys().stream()
            .map(key -> evaluateFeature(userId, key))
            .toList();
    }

    @Override
    @Transactional(noRollbackFor = {QuotaExceededException.class, FeatureEntitlementRequiredException.class})
    public void requireCapacity(UUID userId, FeatureKey featureKey, int currentCount, String operationId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(featureKey, "featureKey");
        if (currentCount < 0) {
            throw new IllegalArgumentException("currentCount must be >= 0");
        }

        Instant now = clock.instant();
        String normalizedOpId = normalizeOptionalOperationId(operationId);
        if (!features.isActive(featureKey)) {
            recordAudit(userId, featureKey, normalizedOpId, "DENIED_UNAVAILABLE", DEFAULT_POLICY_VERSION,
                Map.of("reason", "FEATURE_UNAVAILABLE"), now);
            throw new FeatureEntitlementRequiredException(featureKey);
        }

        boolean hasGrant = grants.hasActiveGrant(userId, featureKey, now);
        String tier = hasGrant ? TIER_PLUS : TIER_FREE;
        Optional<QuotaPolicy> policyOpt = quotaPolicies.findPolicy(featureKey, tier);
        if (policyOpt.isEmpty()) {
            if (!hasGrant) {
                recordAudit(userId, featureKey, normalizedOpId, "DENIED_GRANT_REQUIRED", DEFAULT_POLICY_VERSION,
                    Map.of("tier", tier), now);
                throw new FeatureEntitlementRequiredException(featureKey);
            }
            recordAudit(userId, featureKey, normalizedOpId, "ALLOWED", DEFAULT_POLICY_VERSION,
                Map.of("tier", tier, "currentCount", currentCount), now);
            return;
        }

        QuotaPolicy policy = policyOpt.get();
        int maxAmount = policy.maxAmount();
        if (currentCount >= maxAmount) {
            recordAudit(
                userId,
                featureKey,
                normalizedOpId,
                "DENIED_CAPACITY",
                policy.policyVersion(),
                Map.of("tier", tier, "currentCount", currentCount, "limit", maxAmount),
                now
            );
            throw new QuotaExceededException(featureKey, LIMIT_TYPE_CAPACITY, maxAmount, 0, null);
        }

        recordAudit(
            userId,
            featureKey,
            normalizedOpId,
            "ALLOWED",
            policy.policyVersion(),
            Map.of("tier", tier, "currentCount", currentCount, "limit", maxAmount),
            now
        );
    }

    @Override
    @Transactional(noRollbackFor = {QuotaExceededException.class, FeatureEntitlementRequiredException.class})
    public QuotaReservation reserveQuota(UUID userId, FeatureKey featureKey, String operationId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(featureKey, "featureKey");
        String normalizedOpId = requireOperationId(operationId);

        Instant now = clock.instant();
        if (!features.isActive(featureKey)) {
            recordAudit(userId, featureKey, normalizedOpId, "DENIED_UNAVAILABLE", DEFAULT_POLICY_VERSION,
                Map.of("reason", "FEATURE_UNAVAILABLE"), now);
            throw new FeatureEntitlementRequiredException(featureKey);
        }

        usageQuotas.lockOperation(userId, featureKey, normalizedOpId);

        Optional<UsageReservation> existingOpt = usageQuotas.findReservationByOperation(userId, featureKey, normalizedOpId);
        if (existingOpt.isPresent()) {
            return buildExistingReservationView(userId, featureKey, existingOpt.get(), now);
        }

        String tier = grants.hasActiveGrant(userId, featureKey, now) ? TIER_PLUS : TIER_FREE;
        QuotaPolicy policy = requirePeriodUsagePolicy(featureKey, tier);
        UtcDayWindow window = currentUtcDayWindow(now);

        usageQuotas.ensureCounterExists(userId, featureKey, window.periodKey(), window.resetAt());
        Optional<UsageCounter> updatedOpt = usageQuotas.tryReserveCounterUnit(
            userId, featureKey, window.periodKey(), policy.maxAmount()
        );
        if (updatedOpt.isEmpty()) {
            recordAudit(
                userId,
                featureKey,
                normalizedOpId,
                "DENIED_QUOTA",
                policy.policyVersion(),
                Map.of("tier", tier, "limit", policy.maxAmount(), "periodKey", window.periodKey()),
                now
            );
            throw new QuotaExceededException(
                featureKey, LIMIT_TYPE_PERIOD_USAGE, policy.maxAmount(), 0, window.resetAt()
            );
        }

        UsageCounter counter = updatedOpt.get();
        int remaining = Math.max(0, policy.maxAmount() - counter.consumedAndReserved());
        UUID reservationId = UUID.randomUUID();
        UsageReservation reservation = new UsageReservation(
            reservationId,
            userId,
            featureKey,
            normalizedOpId,
            window.periodKey(),
            1,
            UsageReservation.STATUS_RESERVED,
            policy.policyVersion(),
            now,
            null
        );
        usageQuotas.insertReservation(reservation);
        recordAudit(
            userId,
            featureKey,
            normalizedOpId,
            UsageReservation.STATUS_RESERVED,
            policy.policyVersion(),
            Map.of(
                "reservationId", reservationId.toString(),
                "tier", tier,
                "limit", policy.maxAmount(),
                "remaining", remaining,
                "periodKey", window.periodKey()
            ),
            now
        );

        return new QuotaReservation(
            reservationId,
            featureKey.value(),
            normalizedOpId,
            UsageReservation.STATUS_RESERVED,
            policy.maxAmount(),
            remaining,
            counter.resetAt(),
            policy.policyVersion()
        );
    }

    @Override
    @Transactional
    public void settleQuota(UUID reservationId) {
        Objects.requireNonNull(reservationId, "reservationId");
        Optional<UsageReservation> lockedOpt = usageQuotas.findReservationByIdForUpdate(reservationId);
        if (lockedOpt.isEmpty()) {
            return;
        }
        UsageReservation res = lockedOpt.get();
        if (!UsageReservation.STATUS_RESERVED.equals(res.status())) {
            return;
        }

        Instant now = clock.instant();
        if (usageQuotas.markReservationSettled(reservationId, now)) {
            usageQuotas.settleCounterUnit(res.userId(), res.featureKey(), res.periodKey(), res.amount());
            recordAudit(
                res.userId(),
                res.featureKey(),
                res.operationId(),
                UsageReservation.STATUS_SETTLED,
                res.policyVersion(),
                Map.of(
                    "reservationId", reservationId.toString(),
                    "amount", res.amount(),
                    "periodKey", res.periodKey()
                ),
                now
            );
        }
    }

    @Override
    @Transactional
    public void releaseQuota(UUID reservationId, String reason) {
        Objects.requireNonNull(reservationId, "reservationId");
        Optional<UsageReservation> lockedOpt = usageQuotas.findReservationByIdForUpdate(reservationId);
        if (lockedOpt.isEmpty()) {
            return;
        }
        UsageReservation res = lockedOpt.get();
        if (!UsageReservation.STATUS_RESERVED.equals(res.status())) {
            return;
        }

        Instant now = clock.instant();
        if (usageQuotas.markReservationReleased(reservationId, now)) {
            usageQuotas.releaseCounterUnit(res.userId(), res.featureKey(), res.periodKey(), res.amount());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("reservationId", reservationId.toString());
            detail.put("amount", res.amount());
            detail.put("periodKey", res.periodKey());
            if (reason != null && !reason.isBlank()) {
                detail.put("reason", reason.trim());
            }
            recordAudit(
                res.userId(),
                res.featureKey(),
                res.operationId(),
                UsageReservation.STATUS_RELEASED,
                res.policyVersion(),
                detail,
                now
            );
        }
    }

    @Override
    @Transactional(noRollbackFor = {QuotaExceededException.class, FeatureEntitlementRequiredException.class})
    public QuotaDecision consumeQuota(UUID userId, FeatureKey featureKey, String operationId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(featureKey, "featureKey");
        String normalizedOpId = requireOperationId(operationId);

        Instant now = clock.instant();
        if (!features.isActive(featureKey)) {
            recordAudit(userId, featureKey, normalizedOpId, "DENIED_UNAVAILABLE", DEFAULT_POLICY_VERSION,
                Map.of("reason", "FEATURE_UNAVAILABLE"), now);
            throw new FeatureEntitlementRequiredException(featureKey);
        }

        usageQuotas.lockOperation(userId, featureKey, normalizedOpId);

        Optional<UsageReservation> existingOpt = usageQuotas.findReservationByOperation(userId, featureKey, normalizedOpId);
        if (existingOpt.isPresent()) {
            UsageReservation existing = existingOpt.get();
            if (UsageReservation.STATUS_RESERVED.equals(existing.status())) {
                settleQuota(existing.id());
                existing = usageQuotas.findReservationByOperation(userId, featureKey, normalizedOpId).orElse(existing);
            }
            QuotaReservation view = buildExistingReservationView(userId, featureKey, existing, now);
            boolean allowed = !UsageReservation.STATUS_RELEASED.equals(existing.status());
            return new QuotaDecision(
                featureKey.value(),
                existing.operationId(),
                allowed,
                view.limit(),
                view.remaining(),
                view.resetAt(),
                existing.policyVersion()
            );
        }

        String tier = grants.hasActiveGrant(userId, featureKey, now) ? TIER_PLUS : TIER_FREE;
        QuotaPolicy policy = requirePeriodUsagePolicy(featureKey, tier);
        UtcDayWindow window = currentUtcDayWindow(now);

        usageQuotas.ensureCounterExists(userId, featureKey, window.periodKey(), window.resetAt());
        Optional<UsageCounter> updatedOpt = usageQuotas.tryConsumeCounterUnit(
            userId, featureKey, window.periodKey(), policy.maxAmount()
        );
        if (updatedOpt.isEmpty()) {
            recordAudit(
                userId,
                featureKey,
                normalizedOpId,
                "DENIED_QUOTA",
                policy.policyVersion(),
                Map.of("tier", tier, "limit", policy.maxAmount(), "periodKey", window.periodKey()),
                now
            );
            throw new QuotaExceededException(
                featureKey, LIMIT_TYPE_PERIOD_USAGE, policy.maxAmount(), 0, window.resetAt()
            );
        }

        UsageCounter counter = updatedOpt.get();
        int remaining = Math.max(0, policy.maxAmount() - counter.consumedAndReserved());
        UUID reservationId = UUID.randomUUID();
        UsageReservation settled = new UsageReservation(
            reservationId,
            userId,
            featureKey,
            normalizedOpId,
            window.periodKey(),
            1,
            UsageReservation.STATUS_SETTLED,
            policy.policyVersion(),
            now,
            now
        );
        usageQuotas.insertReservation(settled);
        recordAudit(
            userId,
            featureKey,
            normalizedOpId,
            UsageReservation.STATUS_SETTLED,
            policy.policyVersion(),
            Map.of(
                "reservationId", reservationId.toString(),
                "tier", tier,
                "limit", policy.maxAmount(),
                "remaining", remaining,
                "periodKey", window.periodKey(),
                "mode", "CONSUME"
            ),
            now
        );

        return new QuotaDecision(
            featureKey.value(),
            normalizedOpId,
            true,
            policy.maxAmount(),
            remaining,
            counter.resetAt(),
            policy.policyVersion()
        );
    }

    private QuotaReservation buildExistingReservationView(
        UUID userId,
        FeatureKey featureKey,
        UsageReservation existing,
        Instant now
    ) {
        String tier = grants.hasActiveGrant(userId, featureKey, now) ? TIER_PLUS : TIER_FREE;
        int limit = quotaPolicies.findPolicy(featureKey, tier)
            .map(QuotaPolicy::maxAmount)
            .orElse(0);
        Optional<UsageCounter> counterOpt = usageQuotas.findCounter(userId, featureKey, existing.periodKey());
        int usedAndReserved = counterOpt.map(UsageCounter::consumedAndReserved).orElse(0);
        int remaining = Math.max(0, limit - usedAndReserved);
        Instant resetAt = counterOpt
            .map(UsageCounter::resetAt)
            .orElseGet(() -> LocalDate.parse(existing.periodKey()).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
        return new QuotaReservation(
            existing.id(),
            featureKey.value(),
            existing.operationId(),
            existing.status(),
            limit,
            remaining,
            resetAt,
            existing.policyVersion()
        );
    }

    private QuotaPolicy requirePeriodUsagePolicy(FeatureKey featureKey, String tier) {
        return quotaPolicies.findPolicy(featureKey, tier)
            .filter(policy -> LIMIT_TYPE_PERIOD_USAGE.equals(policy.limitType()))
            .orElseThrow(() -> new IllegalStateException(
                "No PERIOD_USAGE quota policy configured for feature " + featureKey.value() + " and tier " + tier
            ));
    }

    private void recordAudit(
        UUID userId,
        FeatureKey featureKey,
        String operationId,
        String decision,
        String policyVersion,
        Map<String, Object> detail,
        Instant occurredAt
    ) {
        auditLogs.record(new EntitlementAuditEntry(
            UUID.randomUUID(),
            userId,
            featureKey.value(),
            operationId,
            decision,
            policyVersion,
            detail,
            occurredAt
        ));
    }

    private static UtcDayWindow currentUtcDayWindow(Instant now) {
        LocalDate utcDay = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Instant resetAt = utcDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return new UtcDayWindow(utcDay.toString(), resetAt);
    }

    private static String requireOperationId(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId must not be blank");
        }
        return operationId.trim();
    }

    private static String normalizeOptionalOperationId(String operationId) {
        return operationId == null || operationId.isBlank() ? null : operationId.trim();
    }

    private record UtcDayWindow(String periodKey, Instant resetAt) {}
}
