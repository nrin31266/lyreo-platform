package com.lyreo.identity.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        var user = provisioning.provision(jwt.getSubject(), jwt.getClaimAsString("email"));
        return new MeResponse(
            user.id(),
            user.keycloakSubject(),
            user.emailSnapshot() == null ? "" : user.emailSnapshot()
        );
    }

    public record MeResponse(
        UUID id,
        String subject,
        String email
    ) {}
}
