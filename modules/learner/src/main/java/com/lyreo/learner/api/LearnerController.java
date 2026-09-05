package com.lyreo.learner.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.learner.application.LearnerProfileRepository;
import com.lyreo.learner.application.OnboardingService;
import com.lyreo.learner.domain.LearnerPreferences;
import java.util.Map;
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
    public Map<String, Object> profile(@AuthenticationPrincipal Jwt jwt) {
        var user = users.provision(jwt.getSubject(), jwt.getClaimAsString("email"));
        var profile = profiles.findByLearnerId(user.id()).orElse(null);
        return Map.of(
            "userId", user.id(),
            "onboarded", profile != null,
            "profile", profile == null ? Map.of() : profile
        );
    }

    @PutMapping("/onboarding")
    public Object onboarding(@AuthenticationPrincipal Jwt jwt, @RequestBody OnboardingRequest request) {
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
        @RequestBody LearnerPreferences preferences
    ) {
        var user = users.provision(jwt.getSubject(), jwt.getClaimAsString("email"));
        validate(preferences);
        return profiles.savePreferences(user.id(), preferences);
    }

    private static void validate(LearnerPreferences preferences) {
        if (preferences.defaultPlaybackSpeed() < 0.5 || preferences.defaultPlaybackSpeed() > 2.0) {
            throw new IllegalArgumentException("defaultPlaybackSpeed must be between 0.5 and 2.0");
        }
        if (preferences.preferredAccent() == null || preferences.preferredAccent().isBlank()) {
            throw new IllegalArgumentException("preferredAccent is required");
        }
    }

    public record OnboardingRequest(
        String displayName,
        String currentLevel,
        String goal,
        int dailyMinutes,
        String focusArea
    ) {}
}
