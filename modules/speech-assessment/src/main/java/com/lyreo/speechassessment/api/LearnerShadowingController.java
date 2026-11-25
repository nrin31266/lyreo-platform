package com.lyreo.speechassessment.api;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.speechassessment.application.LearnerPronunciationAttempts;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Learner-facing Shadowing pronunciation constraints, submission, polling, audio, and cancel API. */
@RestController
@RequestMapping("/api/v1")
public class LearnerShadowingController {
    private static final String SENTENCE_BASE =
        "/lessons/{lessonId}/activities/{activityId}/shadowing/sentences/{sentenceId}";
    private static final String ATTEMPT_BASE = "/shadowing-attempts/{id}";

    private final LearnerPronunciationAttempts attempts;
    private final AppUserProvisioningService users;

    public LearnerShadowingController(
        LearnerPronunciationAttempts attempts,
        AppUserProvisioningService users
    ) {
        this.attempts = attempts;
        this.users = users;
    }

    @GetMapping(SENTENCE_BASE + "/constraints")
    public LearnerPronunciationAttempts.Constraints constraints(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID lessonId,
        @PathVariable UUID activityId,
        @PathVariable UUID sentenceId,
        @RequestParam long revision
    ) {
        return attempts.constraints(provision(jwt), lessonId, activityId, sentenceId, revision);
    }

    @PostMapping(value = SENTENCE_BASE + "/attempts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<LearnerPronunciationAttempts.Accepted> submit(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID lessonId,
        @PathVariable UUID activityId,
        @PathVariable UUID sentenceId,
        @RequestHeader("Idempotency-Key") UUID key,
        @RequestParam long revision,
        @RequestPart("audio") MultipartFile audio
    ) throws IOException {
        if (audio.isEmpty() || audio.getSize() > attempts.maxUploadBytes()) {
            throw new RequestValidationException("Recording size exceeds the allowed upload limits");
        }
        var accepted = attempts.submit(
            provision(jwt),
            key,
            lessonId,
            activityId,
            sentenceId,
            revision,
            audio.getBytes()
        );
        return ResponseEntity.accepted()
            .location(URI.create("/api/v1/shadowing-attempts/" + accepted.attemptId()))
            .cacheControl(CacheControl.noStore())
            .body(accepted);
    }

    @GetMapping(ATTEMPT_BASE)
    public ResponseEntity<LearnerPronunciationAttempts.View> view(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(attempts.view(provision(jwt), id));
    }

    @GetMapping(ATTEMPT_BASE + "/audio")
    public ResponseEntity<Resource> audio(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id
    ) throws IOException {
        byte[] data = attempts.recording(provision(jwt), id);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .header("Accept-Ranges", "bytes")
            .contentType(MediaType.parseMediaType("audio/wav"))
            .contentLength(data.length)
            .body(new ByteArrayResource(data));
    }

    @PostMapping(ATTEMPT_BASE + "/cancel")
    public ResponseEntity<Void> cancel(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id
    ) {
        attempts.cancel(provision(jwt), id);
        return ResponseEntity.accepted().build();
    }

    private UUID provision(Jwt jwt) {
        return users.provision(jwt.getSubject(), jwt.getClaimAsString("email")).id();
    }
}
