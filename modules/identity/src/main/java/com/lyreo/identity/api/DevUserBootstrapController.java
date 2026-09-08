package com.lyreo.identity.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("dev")
@RequestMapping("/internal/dev/bootstrap")
public class DevUserBootstrapController {
    private final AppUserProvisioningService provisioning;
    private final String expectedToken;

    public DevUserBootstrapController(
        AppUserProvisioningService provisioning,
        @Value("${lyreo.security.dev-bootstrap-token:disabled}") String expectedToken
    ) {
        this.provisioning = provisioning;
        this.expectedToken = expectedToken;
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void users(
        @RequestHeader("X-Lyreo-Dev-Bootstrap-Token") String token,
        @RequestBody List<DevUser> users
    ) {
        if ("disabled".equals(expectedToken) || !constantTimeEquals(token, expectedToken)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        users.forEach(user -> provisioning.provision(user.subject(), user.email()));
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    public record DevUser(String subject, String email) {}
}
