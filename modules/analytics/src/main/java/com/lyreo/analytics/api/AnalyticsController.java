package com.lyreo.analytics.api;

import com.lyreo.analytics.application.AnalyticsService;
import com.lyreo.analytics.domain.AnalyticsOverview;
import com.lyreo.identity.application.AppUserProvisioningService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {
    private final AnalyticsService analytics;
    private final AppUserProvisioningService users;

    public AnalyticsController(
        AnalyticsService analytics,
        AppUserProvisioningService users
    ) {
        this.analytics = analytics;
        this.users = users;
    }

    @GetMapping("/overview")
    public AnalyticsOverview overview(@AuthenticationPrincipal Jwt jwt) {
        return analytics.overview(learnerId(jwt));
    }

    private UUID learnerId(Jwt jwt) {
        return users.provision(jwt.getSubject(), jwt.getClaimAsString("email")).id();
    }
}
