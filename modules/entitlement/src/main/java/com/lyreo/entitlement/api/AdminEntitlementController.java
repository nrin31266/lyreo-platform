package com.lyreo.entitlement.api;

import com.lyreo.entitlement.application.internal.EntitlementGrantAdminService;
import com.lyreo.entitlement.application.port.FeatureRepository;
import com.lyreo.entitlement.domain.EntitlementAuditEntry;
import com.lyreo.entitlement.domain.EntitlementGrant;
import com.lyreo.entitlement.domain.QuotaPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/entitlements")
@PreAuthorize("hasRole('ADMIN')")
public class AdminEntitlementController {
    private final EntitlementGrantAdminService grantAdmin;

    public AdminEntitlementController(EntitlementGrantAdminService grantAdmin) {
        this.grantAdmin = grantAdmin;
    }

    @GetMapping("/grants")
    public List<GrantResponse> listGrants(
        @RequestParam(required = false) UUID userId,
        @RequestParam(required = false) String featureKey
    ) {
        return grantAdmin.listGrants(userId, featureKey).stream()
            .map(GrantResponse::from)
            .toList();
    }

    @PostMapping("/grants")
    public ResponseEntity<GrantResponse> createGrant(
        @AuthenticationPrincipal Jwt admin,
        @Valid @RequestBody CreateGrantRequest request
    ) {
        String actor = admin != null && admin.getSubject() != null ? admin.getSubject() : "admin";
        EntitlementGrant created = grantAdmin.createGrant(
            request.userId(),
            request.featureKey(),
            request.validFrom(),
            request.validUntil(),
            request.sourceType(),
            request.sourceReference(),
            actor
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(GrantResponse.from(created));
    }

    @PostMapping("/grants/{grantId}/revoke")
    public GrantResponse revokeGrant(
        @PathVariable UUID grantId,
        @AuthenticationPrincipal Jwt admin,
        @RequestBody(required = false) @Valid RevokeGrantRequest request
    ) {
        String actor = admin != null && admin.getSubject() != null ? admin.getSubject() : "admin";
        String reason = request == null ? null : request.reason();
        return GrantResponse.from(grantAdmin.revokeGrant(grantId, actor, reason));
    }

    @GetMapping("/features")
    public List<FeatureResponse> listFeatures() {
        return grantAdmin.listFeatures().stream()
            .map(f -> new FeatureResponse(f.featureKey(), f.displayName(), f.description(), f.status()))
            .toList();
    }

    @GetMapping("/quotas")
    public List<QuotaPolicyResponse> listQuotas() {
        return grantAdmin.listQuotas().stream()
            .map(QuotaPolicyResponse::from)
            .toList();
    }

    @PutMapping("/quotas")
    public QuotaPolicyResponse upsertQuota(
        @AuthenticationPrincipal Jwt admin,
        @Valid @RequestBody UpsertQuotaRequest request
    ) {
        String actor = admin != null && admin.getSubject() != null ? admin.getSubject() : "admin";
        return QuotaPolicyResponse.from(grantAdmin.upsertQuota(
            request.featureKey(),
            request.tier(),
            request.limitType(),
            request.periodUnit(),
            request.maxAmount(),
            request.policyVersion(),
            actor
        ));
    }

    @GetMapping("/audit")
    public List<AuditResponse> listAudit(
        @RequestParam(required = false) UUID userId,
        @RequestParam(required = false) String featureKey,
        @RequestParam(defaultValue = "50") int limit
    ) {
        return grantAdmin.listAudit(userId, featureKey, limit).stream()
            .map(AuditResponse::from)
            .toList();
    }

    public record FeatureResponse(String featureKey, String displayName, String description, String status) {}

    public record QuotaPolicyResponse(
        String featureKey,
        String tier,
        String limitType,
        String periodUnit,
        int maxAmount,
        String policyVersion
    ) {
        static QuotaPolicyResponse from(QuotaPolicy policy) {
            return new QuotaPolicyResponse(
                policy.featureKey().value(),
                policy.tier(),
                policy.limitType(),
                policy.periodUnit(),
                policy.maxAmount(),
                policy.policyVersion()
            );
        }
    }

    public record UpsertQuotaRequest(
        @NotBlank String featureKey,
        @NotBlank String tier,
        @NotBlank String limitType,
        @NotBlank String periodUnit,
        @Min(0) int maxAmount,
        String policyVersion
    ) {}

    public record AuditResponse(
        UUID id,
        UUID userId,
        String featureKey,
        String operationId,
        String decision,
        String policyVersion,
        Map<String, Object> detail,
        Instant occurredAt
    ) {
        static AuditResponse from(EntitlementAuditEntry entry) {
            return new AuditResponse(
                entry.id(),
                entry.userId(),
                entry.featureKey(),
                entry.operationId(),
                entry.decision(),
                entry.policyVersion(),
                entry.detail(),
                entry.occurredAt()
            );
        }
    }

    public record CreateGrantRequest(
        @NotNull UUID userId,
        @NotBlank @Size(max = 64) String featureKey,
        Instant validFrom,
        Instant validUntil,
        @Size(max = 64) String sourceType,
        @Size(max = 500) String sourceReference
    ) {}

    public record RevokeGrantRequest(
        @Size(max = 500) String reason
    ) {}

    public record GrantResponse(
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
        static GrantResponse from(EntitlementGrant grant) {
            return new GrantResponse(
                grant.id(),
                grant.userId(),
                grant.featureKey(),
                grant.status(),
                grant.validFrom(),
                grant.validUntil(),
                grant.sourceType(),
                grant.sourceReference(),
                grant.createdBy(),
                grant.createdAt(),
                grant.revokedAt(),
                grant.revokedBy(),
                grant.revocationReason()
            );
        }
    }
}
