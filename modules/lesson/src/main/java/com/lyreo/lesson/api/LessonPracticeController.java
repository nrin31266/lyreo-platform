package com.lyreo.lesson.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.lesson.application.LessonPracticeService;
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
    public LessonPracticeService.DictationAttemptResult dictationAttempt(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID lessonId,
        @PathVariable UUID activityId,
        @RequestBody DictationAttemptRequest request
    ) {
        UUID learnerId = users.provision(
            jwt.getSubject(),
            jwt.getClaimAsString("email")
        ).id();

        return practice.submitDictation(
            learnerId,
            lessonId,
            activityId,
            request.sentenceId(),
            request.answer()
        );
    }

    public record DictationAttemptRequest(UUID sentenceId, String answer) {}
}
