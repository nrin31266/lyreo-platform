package com.lyreo.identity.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
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
    public MeResponse me(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        var user = provisioning.provision(jwt.getSubject(), jwt.getClaimAsString("email"));
        List<String> roles = authentication == null ? List.of() : authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(Objects::nonNull)
            .map(auth -> auth.startsWith("ROLE_") ? auth.substring(5) : auth)
            .distinct()
            .sorted()
            .toList();
        return new MeResponse(
            user.id(),
            user.keycloakSubject(),
            user.emailSnapshot() == null ? "" : user.emailSnapshot(),
            roles
        );
    }

    public record MeResponse(
        UUID id,
        String subject,
        String email,
        List<String> roles
    ) {
        public MeResponse(UUID id, String subject, String email) {
            this(id, subject, email, List.of());
        }
    }
}
