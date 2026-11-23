package com.lyreo.learner.api;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.learner.application.LearnerProfileService;
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
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

@RestController
@RequestMapping("/api/v1/learner")
public class LearnerController {
    private static final String GOAL_CODE = "CONVERSATION|WORK|TOEIC|UNDECIDED";
    private static final String LEVEL_CODE = "[ABC][12]|UNKNOWN";

    private final AppUserProvisioningService users;
    private final LearnerProfileService profiles;
    private final OnboardingService onboarding;

    public LearnerController(
        AppUserProvisioningService users,
        LearnerProfileService profiles,
        OnboardingService onboarding
    ) {
        this.users = users;
        this.profiles = profiles;
        this.onboarding = onboarding;
    }

    @GetMapping("/profile")
    public LearnerProfileResponse profile(@AuthenticationPrincipal Jwt jwt) {
        var user = users.provision(jwt.getSubject(), jwt.getClaimAsString("email"));
        var profile = profiles.findByLearnerId(user.id()).filter(LearnerProfile::onboarded).orElse(null);
        return new LearnerProfileResponse(
            user.id(),
            profile != null,
            LearnerProfileDto.from(profile)
        );
    }

    @PutMapping("/onboarding")
    public LearnerProfileDto onboarding(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody OnboardingRequest request
    ) {
        var user = users.provision(jwt.getSubject(), jwt.getClaimAsString("email"));
        var profile = onboarding.onboard(user.id(), LearnerProfile.Goal.valueOf(request.goal()),
            LearnerProfile.Level.valueOf(request.currentLevel()), request.dailyMinutes());
        return LearnerProfileDto.from(profile);
    }

    @PatchMapping("/profile")
    public LearnerProfileDto patchProfile(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody LearnerProfilePatchRequest request
    ) {
        var user = users.provision(jwt.getSubject(), jwt.getClaimAsString("email"));
        return LearnerProfileDto.from(profiles.patchProfile(user.id(), request.toDomain()));
    }

    @PutMapping(value = "/profile/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LearnerProfileDto uploadAvatar(@AuthenticationPrincipal Jwt jwt, @RequestPart("file") MultipartFile file) {
        var user = users.provision(jwt.getSubject(), jwt.getClaimAsString("email"));
        return LearnerProfileDto.from(profiles.uploadOwnAvatar(user.id(), LearnerAvatarImage.normalize(file)));
    }

    @GetMapping(value = "/profile/avatar", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> avatar(@AuthenticationPrincipal Jwt jwt) {
        var user = users.provision(jwt.getSubject(), jwt.getClaimAsString("email"));
        return LearnerAvatarImage.response(profiles.avatar(user.id()));
    }

    @GetMapping("/preferences")
    public LearnerPreferencesResponse preferences(@AuthenticationPrincipal Jwt jwt) {
        var user = users.provision(jwt.getSubject(), jwt.getClaimAsString("email"));
        return LearnerPreferencesResponse.from(profiles.findPreferences(user.id()));
    }

    @PutMapping("/preferences")
    public LearnerPreferencesResponse preferences(
        @AuthenticationPrincipal Jwt jwt,
        @Valid @RequestBody UpdatePreferencesRequest request
    ) {
        var user = users.provision(jwt.getSubject(), jwt.getClaimAsString("email"));
        var saved = profiles.savePreferences(user.id(), request.toDomain());
        return LearnerPreferencesResponse.from(saved);
    }

    public record LearnerProfileResponse(
        UUID userId,
        boolean onboarded,
        LearnerProfileDto profile
    ) {}

    public record LearnerProfileDto(
        UUID learnerId,
        String displayName,
        String currentLevel,
        String goal,
        Integer dailyMinutes,
        String focusArea,
        LearnerPreferencesResponse preferences,
        LearnerProfile.StarterSuggestion starterSuggestion,
        String avatarPreset,
        UUID avatarUploadId
    ) {
        public static LearnerProfileDto from(LearnerProfile p) {
            if (p == null) return null;
            return new LearnerProfileDto(
                p.learnerId(),
                p.displayName(),
                p.currentLevel(),
                p.goal(),
                p.dailyMinutes(),
                p.focusArea(),
                LearnerPreferencesResponse.from(p.preferences()),
                p.starterSuggestion(),
                p.avatarPreset(),
                p.avatarUploadId()
            );
        }
    }

    public record LearnerPreferencesResponse(
        String preferredAccent,
        LearnerPreferences.DisplayTiming translation,
        LearnerPreferences.DisplayTiming sentenceIpa,
        LearnerPreferences.DisplayTiming vocabularyNotes,
        LearnerPreferences.DisplayTiming grammarNotes,
        boolean thoughtGroups,
        boolean karaokeHighlighting,
        boolean properNounHints,
        double defaultPlaybackSpeed
    ) {
        public static LearnerPreferencesResponse from(LearnerPreferences p) {
            if (p == null) return null;
            return new LearnerPreferencesResponse(
                p.preferredAccent(),
                p.translation(),
                p.sentenceIpa(),
                p.vocabularyNotes(),
                p.grammarNotes(),
                p.thoughtGroups(),
                p.karaokeHighlighting(),
                p.properNounHints(),
                p.defaultPlaybackSpeed()
            );
        }
    }

    public record OnboardingRequest(
        @NotNull @Pattern(regexp = GOAL_CODE) String goal,
        @NotNull @Pattern(regexp = LEVEL_CODE) String currentLevel,
        @NotNull
        @JsonDeserialize(using = DailyMinutesDeserializer.class)
        @Min(value = 5, message = "dailyMinutes must be at least 5")
        @Max(value = 240, message = "dailyMinutes must be at most 240")
        Integer dailyMinutes
    ) {}

    public static final class LearnerProfilePatchRequest extends LearnerAppearancePatchRequest {
        @JsonSetter(nulls = Nulls.FAIL)
        @Pattern(regexp = GOAL_CODE)
        public String goal;
        @JsonSetter(nulls = Nulls.FAIL)
        @Pattern(regexp = LEVEL_CODE)
        public String currentLevel;
        @JsonSetter(nulls = Nulls.FAIL)
        @JsonDeserialize(using = DailyMinutesDeserializer.class)
        @Min(5) @Max(240)
        public Integer dailyMinutes;

        @Pattern(regexp = "LISTENING|SPEAKING|VOCABULARY|GRAMMAR")
        private String focusArea;
        private boolean changeFocusArea;

        @JsonSetter("focusArea")
        public void setFocusArea(String focus) {
            changeFocusArea = true;
            focusArea = focus;
        }

        LearnerProfile.Patch toDomain() {
            return new LearnerProfile.Patch(
                appearance(),
                goal == null ? null : LearnerProfile.Goal.valueOf(goal),
                currentLevel == null ? null : LearnerProfile.Level.valueOf(currentLevel),
                dailyMinutes, changeFocusArea,
                focusArea == null ? null : LearnerProfile.FocusArea.valueOf(focusArea)
            );
        }
    }

    public static final class DailyMinutesDeserializer extends ValueDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Integer.class, "dailyMinutes must be an integer");
            }
            return parser.getIntValue();
        }
    }

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
