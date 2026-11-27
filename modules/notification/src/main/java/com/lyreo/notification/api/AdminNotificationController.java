package com.lyreo.notification.api;

import com.lyreo.notification.application.LearnerNotificationService;
import com.lyreo.notification.application.LearnerNotificationService.ActivityPage;
import com.lyreo.notification.domain.LearnerNotification;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin CRUD for human-readable learner announcements and reminders. */
@RestController
@RequestMapping("/api/v1/admin/notifications")
@PreAuthorize("hasRole('ADMIN')")
public class AdminNotificationController {
    private final LearnerNotificationService service;

    public AdminNotificationController(LearnerNotificationService service) {
        this.service = service;
    }

    @GetMapping
    public List<LearnerNotification> list() {
        return service.listAllForAdmin();
    }

    @GetMapping("/activity")
    public ActivityPage activity(@RequestParam(required = false) String category,
                                 @RequestParam(defaultValue = "0") int page) {
        return service.listActivity(category, page);
    }

    @PostMapping
    public ResponseEntity<LearnerNotification> create(
        @Valid @RequestBody SaveNotificationRequest request,
        @AuthenticationPrincipal Jwt jwt
    ) {
        String actor = jwt == null ? "admin" : jwt.getSubject();
        LearnerNotification created = service.create(toCommand(request), actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public LearnerNotification update(
        @PathVariable UUID id,
        @Valid @RequestBody SaveNotificationRequest request,
        @AuthenticationPrincipal Jwt jwt
    ) {
        String actor = jwt == null ? "admin" : jwt.getSubject();
        return service.update(id, toCommand(request), actor);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        service.delete(id, jwt == null ? "admin" : jwt.getSubject());
    }

    private static LearnerNotificationService.SaveCommand toCommand(SaveNotificationRequest r) {
        return new LearnerNotificationService.SaveCommand(
            r.title(),
            r.body(),
            r.category(),
            r.targetAudience(),
            r.status(),
            r.publishedAt(),
            r.expiresAt(),
            r.recipientIds()
        );
    }

    public record SaveNotificationRequest(
        @NotBlank String title,
        @NotBlank String body,
        String category,
        String targetAudience,
        String status,
        Instant publishedAt,
        Instant expiresAt,
        @Size(max = 100) List<UUID> recipientIds
    ) {}
}
