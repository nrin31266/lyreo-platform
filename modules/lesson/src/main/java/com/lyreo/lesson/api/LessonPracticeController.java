package com.lyreo.lesson.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.lesson.application.LessonPracticeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Learner-facing Lesson practice API. Scores are always computed by the server. */
@RestController
@RequestMapping("/api/v1/lessons")
public class LessonPracticeController {
    private final LessonPracticeService practice;
    private final AppUserProvisioningService users;

    public LessonPracticeController(
        LessonPracticeService practice,
        AppUserProvisioningService users
    ) {
        this.practice = practice;
        this.users = users;
    }

    @PostMapping("/{lessonId}/activities/{activityId}/dictation/attempts")
    public DictationAttemptResponse dictationAttempt(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID lessonId,
        @PathVariable UUID activityId,
        @Valid @RequestBody DictationAttemptRequest request
    ) {
        UUID learnerId = users.provision(
            jwt.getSubject(),
            jwt.getClaimAsString("email")
        ).id();

        return DictationAttemptResponse.from(practice.submitDictation(
            learnerId,
            lessonId,
            activityId,
            request.sentenceId(),
            request.answer()
        ));
    }

    public record DictationAttemptRequest(
        @NotNull(message = "sentenceId is required")
        UUID sentenceId,
        @NotBlank(message = "answer is required")
        String answer
    ) {}

    public record DictationAttemptResponse(
        UUID attemptId,
        int serverScore,
        String expectedText,
        int completedItems,
        int totalItems,
        boolean activityCompleted,
        boolean lessonCompleted
    ) {
        public static DictationAttemptResponse from(LessonPracticeService.DictationAttemptResult result) {
            return new DictationAttemptResponse(
                result.attemptId(),
                result.serverScore(),
                result.expectedText(),
                result.completedItems(),
                result.totalItems(),
                result.activityCompleted(),
                result.lessonCompleted()
            );
        }
    }
}
