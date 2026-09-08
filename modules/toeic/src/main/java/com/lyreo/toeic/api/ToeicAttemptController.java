package com.lyreo.toeic.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.toeic.application.ToeicAttemptService;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/toeic/tests")
public class ToeicAttemptController {
    private final ToeicAttemptService attempts;
    private final AppUserProvisioningService users;

    public ToeicAttemptController(
        ToeicAttemptService attempts,
        AppUserProvisioningService users
    ) {
        this.attempts = attempts;
        this.users = users;
    }

    @PostMapping("/{testId}/attempts")
    public ToeicAttemptService.SubmitResult submit(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID testId,
        @RequestBody SubmitRequest request
    ) {
        UUID learnerId = users.provision(
            jwt.getSubject(),
            jwt.getClaimAsString("email")
        ).id();
        return attempts.submit(learnerId, testId, request.mode(), request.answers());
    }

    public record SubmitRequest(
        ToeicAttemptService.Mode mode,
        Map<UUID, String> answers
    ) {}
}
