package com.lyreo.identity.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import java.security.Principal;
import java.util.Map;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {
    private final AppUserProvisioningService provisioning;

    public MeController(AppUserProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @GetMapping
    public Map<String, Object> me(@AuthenticationPrincipal Jwt jwt) {
        var user = provisioning.provision(jwt.getSubject(), jwt.getClaimAsString("email"));
        return Map.of(
            "id", user.id(),
            "subject", user.keycloakSubject(),
            "email", user.emailSnapshot() == null ? "" : user.emailSnapshot()
        );
    }
}
