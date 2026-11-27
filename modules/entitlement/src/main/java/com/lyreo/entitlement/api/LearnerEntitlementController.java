package com.lyreo.entitlement.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/v1/entitlements", "/api/v1/learner/entitlements"})
public class LearnerEntitlementController {
    private final EntitlementService entitlements;
    private final AppUserProvisioningService users;

    public LearnerEntitlementController(
        EntitlementService entitlements,
        AppUserProvisioningService users
    ) {
        this.entitlements = entitlements;
        this.users = users;
    }

    @GetMapping
    public LearnerEntitlementsResponse list(@AuthenticationPrincipal Jwt jwt) {
        var user = users.provision(jwt.getSubject(), jwt.getClaimAsString("email"));
        var plan = entitlements.currentPlan(user.id());
        return new LearnerEntitlementsResponse(
            user.id(),
            plan.tier(),
            plan.validUntil(),
            entitlements.listLearnerEntitlements(user.id())
        );
    }

    public record LearnerEntitlementsResponse(
        UUID learnerId,
        String tier,
        Instant validUntil,
        List<FeatureAccess> entitlements
    ) {}
}
