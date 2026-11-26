package com.lyreo.chat.api;

import com.lyreo.chat.application.SpeakingRoleplayService;
import com.lyreo.chat.domain.SpeakingSessionDetail;
import com.lyreo.identity.application.AppUserProvisioningService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Learner-facing Real-World AI Voice Roleplay scenario catalog & conversation turns API. */
@RestController
@RequestMapping("/api/v1/speaking")
public class LearnerSpeakingController {
    private final SpeakingRoleplayService roleplay;
    private final AppUserProvisioningService users;

    public LearnerSpeakingController(
        SpeakingRoleplayService roleplay,
        AppUserProvisioningService users
    ) {
        this.roleplay = roleplay;
        this.users = users;
    }

    @GetMapping("/scenarios")
    public List<SpeakingRoleplayService.SpeakingScenarioView> listScenarios(@AuthenticationPrincipal Jwt jwt) {
        return roleplay.listPublishedForLearner(learnerId(jwt));
    }

    @PostMapping("/sessions")
    public ResponseEntity<SpeakingSessionDetail> startSession(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody StartSessionRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(roleplay.startSession(learnerId(jwt), request.scenarioId()));
    }

    @GetMapping("/sessions/{id}")
    public SpeakingSessionDetail getSession(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id
    ) {
        return roleplay.getSession(learnerId(jwt), id);
    }

    @PostMapping("/sessions/{id}/turns")
    public SpeakingSessionDetail sendTurn(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id,
        @Valid @RequestBody SendTurnRequest request
    ) {
        return roleplay.sendLearnerTurn(learnerId(jwt), id, request.text(), request.audioAssetId());
    }

    @PostMapping("/sessions/{id}/complete")
    public SpeakingSessionDetail completeSession(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id
    ) {
        return roleplay.completeSession(learnerId(jwt), id);
    }

    private UUID learnerId(Jwt jwt) {
        return users.provision(jwt.getSubject(), jwt.getClaimAsString("email")).id();
    }

    public record StartSessionRequest(@NotNull UUID scenarioId) {}

    public record SendTurnRequest(@NotBlank String text, UUID audioAssetId) {}
}
