package com.lyreo.lesson.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.lesson.application.port.LearnerLessonQuery;
import com.lyreo.lesson.application.practice.LearnerLessonService;
import com.lyreo.lesson.application.practice.LessonPracticeService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
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
import org.springframework.web.bind.annotation.RestController;

/** Learner-facing Lesson catalog, detail, hint-masked activity, audio streaming, and completion API. */
@RestController
@RequestMapping("/api/v1/lessons")
public class LearnerLessonController {
    private final LearnerLessonService lessons;
    private final LessonPracticeService practice;
    private final AppUserProvisioningService users;

    public LearnerLessonController(
        LearnerLessonService lessons,
        LessonPracticeService practice,
        AppUserProvisioningService users
    ) {
        this.lessons = lessons;
        this.practice = practice;
        this.users = users;
    }

    @GetMapping
    public LearnerLessonQuery.CatalogPage list(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(required = false) UUID categoryId,
        @RequestParam(required = false) String cefr,
        @RequestParam(required = false) String sourceType,
        @RequestParam(required = false) String activity,
        @RequestParam(defaultValue = "") @Size(max = 200) String q,
        @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        UUID learnerId = provision(jwt);
        return lessons.listCatalog(learnerId, categoryId, cefr, sourceType, activity, q, page, size);
    }

    @GetMapping("/categories")
    public List<LearnerLessonQuery.CategoryFilterItem> categories() {
        return lessons.listCategories();
    }

    @GetMapping("/{lessonId}")
    public LearnerLessonQuery.LessonDetail detail(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID lessonId
    ) {
        UUID learnerId = provision(jwt);
        return lessons.getLessonDetail(learnerId, lessonId);
    }

    @GetMapping("/{lessonId}/activities/{activityId}")
    public LearnerLessonService.ActivityView activity(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID lessonId,
        @PathVariable UUID activityId,
        @RequestParam(defaultValue = "NONE") String hintLevel,
        @RequestParam(required = false) Boolean properNounHints
    ) {
        UUID learnerId = provision(jwt);
        return lessons.getActivity(learnerId, lessonId, activityId, hintLevel, properNounHints);
    }

    @GetMapping("/{lessonId}/audio")
    public ResponseEntity<Resource> audio(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID lessonId
    ) {
        UUID learnerId = provision(jwt);
        var audio = lessons.readAudio(learnerId, lessonId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(audio.contentType()))
            .cacheControl(CacheControl.noStore())
            .contentLength(audio.bytes().length)
            .header("X-Content-Type-Options", "nosniff")
            .header("Accept-Ranges", "bytes")
            .body(new ByteArrayResource(audio.bytes()));
    }

    @GetMapping("/{lessonId}/attempts")
    public LearnerLessonQuery.AttemptPage attempts(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID lessonId,
        @RequestParam(required = false) UUID activityId,
        @RequestParam(required = false) String activityType,
        @RequestParam(required = false) UUID sentenceId,
        @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        UUID learnerId = provision(jwt);
        return lessons.listAttempts(learnerId, lessonId, activityId, activityType, sentenceId, page, size);
    }

    @PostMapping("/{lessonId}/activities/{activityId}/complete")
    public LessonPracticeService.WatchCompletionResult completeWatch(
        @AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID lessonId,
        @PathVariable UUID activityId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        UUID learnerId = provision(jwt);
        return practice.completeWatch(learnerId, lessonId, activityId, idempotencyKey);
    }

    private UUID provision(Jwt jwt) {
        return users.provision(jwt.getSubject(), jwt.getClaimAsString("email")).id();
    }
}
