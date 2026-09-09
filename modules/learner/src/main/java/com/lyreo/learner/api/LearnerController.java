package com.lyreo.learner.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.learner.application.LearnerProfileRepository;
import com.lyreo.learner.application.OnboardingService;
import com.lyreo.learner.domain.LearnerPreferences;
import com.lyreo.learner.domain.LearnerProfile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/learner")
public class LearnerController {
    private final AppUserProvisioningService users;
    private final LearnerProfileRepository profiles;
    private final OnboardingService onboarding;

    public LearnerController(
        AppUserProvisioningService users,
        LearnerProfileRepository profiles,
        OnboardingService onboarding
    ) {
        this.users = users;
        this.profiles = profiles;
        this.onboarding = onboarding;
    }

    @GetMapping("/profile")
    public LearnerProfileResponse profile(@AuthenticationPrincipal Jwt jwt) {
        var user = users.provision(jwt.getSubject(), jwt.getClaimAsString("email"));
        var profile = profiles.findByLearnerId(user.id()).orElse(null);
        return new LearnerProfileResponse(
            user.id(),
            profile != null,
            profile
        );
    }

    @PutMapping("/onboarding")
    public LearnerProfile onboarding(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody OnboardingRequest request
    ) {
        var user = users.provision(jwt.getSubject(), jwt.getClaimAsString("email"));
        return onboarding.onboard(
            user.id(), request.displayName(), request.currentLevel(), request.goal(),
            request.dailyMinutes(), request.focusArea()
        );
    }

    @GetMapping("/preferences")
    public LearnerPreferences preferences(@AuthenticationPrincipal Jwt jwt) {
        var user = users.provision(jwt.getSubject(), jwt.getClaimAsString("email"));
        return profiles.findByLearnerId(user.id())
            .map(profile -> profile.preferences())
            .orElseGet(LearnerPreferences::defaults);
    }

    @PutMapping("/preferences")
    public LearnerPreferences preferences(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody UpdatePreferencesRequest request
    ) {
        var user = users.provision(jwt.getSubject(), jwt.getClaimAsString("email"));
        return profiles.savePreferences(user.id(), request.toDomain());
    }

    public record LearnerProfileResponse(
        UUID userId,
        boolean onboarded,
        LearnerProfile profile
    ) {}

    public record OnboardingRequest(
        @NotBlank(message = "displayName is required")
        String displayName,
        String currentLevel,
        String goal,
        @Min(value = 5, message = "dailyMinutes must be at least 5")
        @Max(value = 240, message = "dailyMinutes must be at most 240")
        int dailyMinutes,
        String focusArea
    ) {}

    public record UpdatePreferencesRequest(
        @NotBlank(message = "preferredAccent is required")
        String preferredAccent,
        @NotNull(message = "translation is required")
        LearnerPreferences.DisplayTiming translation,
        @NotNull(message = "sentenceIpa is required")
        LearnerPreferences.DisplayTiming sentenceIpa,
        @NotNull(message = "vocabularyNotes is required")
        LearnerPreferences.DisplayTiming vocabularyNotes,
        @NotNull(message = "grammarNotes is required")
        LearnerPreferences.DisplayTiming grammarNotes,
        boolean thoughtGroups,
        boolean karaokeHighlighting,
        boolean properNounHints,
        @DecimalMin(value = "0.5", message = "defaultPlaybackSpeed must be between 0.5 and 2.0")
        @DecimalMax(value = "2.0", message = "defaultPlaybackSpeed must be between 0.5 and 2.0")
        double defaultPlaybackSpeed
    ) {
        public LearnerPreferences toDomain() {
            return new LearnerPreferences(
                preferredAccent,
                translation,
                sentenceIpa,
                vocabularyNotes,
                grammarNotes,
                thoughtGroups,
                karaokeHighlighting,
                properNounHints,
                defaultPlaybackSpeed
            );
        }
    }
}
