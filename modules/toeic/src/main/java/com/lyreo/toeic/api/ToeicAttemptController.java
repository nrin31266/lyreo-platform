package com.lyreo.toeic.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.toeic.application.ToeicAttemptService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
    public ToeicSubmitResponse submit(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID testId,
        @Valid @RequestBody SubmitRequest request
    ) {
        UUID learnerId = users.provision(
            jwt.getSubject(),
            jwt.getClaimAsString("email")
        ).id();
        ToeicAttemptService.Mode serviceMode = ToeicAttemptService.Mode.valueOf(request.mode().name());
        var result = attempts.submit(learnerId, testId, serviceMode, request.answers());
        var score = result.score();
        return new ToeicSubmitResponse(
            result.attemptId(),
            new ToeicScoreResponse(
                score.listeningCorrect(),
                score.listeningTotal(),
                score.readingCorrect(),
                score.readingTotal(),
                score.listeningScaledScore(),
                score.readingScaledScore()
            )
        );
    }

    public enum AttemptMode {
        FULL_TEST,
        DRILL
    }

    public record SubmitRequest(
        @NotNull(message = "mode is required")
        AttemptMode mode,
        Map<UUID, String> answers
    ) {}

    public record ToeicScoreResponse(
        int listeningCorrect,
        int listeningTotal,
        int readingCorrect,
        int readingTotal,
        Integer listeningScaledScore,
        Integer readingScaledScore
    ) {}

    public record ToeicSubmitResponse(
        UUID attemptId,
        ToeicScoreResponse score
    ) {}
}
