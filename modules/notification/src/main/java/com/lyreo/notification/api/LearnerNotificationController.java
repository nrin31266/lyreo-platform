package com.lyreo.notification.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.notification.application.LearnerNotificationService;
import com.lyreo.notification.domain.LearnerNotification;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Learner-facing feed of published announcements & study reminders. */
@Validated
@RestController
@RequestMapping("/api/v1/notifications")
@PreAuthorize("hasRole('LEARNER')")
public class LearnerNotificationController {
    private final LearnerNotificationService service;
    private final AppUserProvisioningService users;

    public LearnerNotificationController(
        LearnerNotificationService service,
        AppUserProvisioningService users
    ) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    public NotificationFeedResponse feed(
        @AuthenticationPrincipal Jwt jwt,
        @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
        @RequestParam(defaultValue = "false") boolean unreadOnly
    ) {
        UUID learnerId = learnerId(jwt);
        LearnerNotificationService.NotificationPage result =
            service.listForLearnerPage(learnerId, page, size, unreadOnly);
        return new NotificationFeedResponse(
            result.items().stream().map(Announcement::from).toList(),
            result.total(),
            result.page(),
            result.size(),
            result.hasMore(),
            result.unreadCount()
        );
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        service.markRead(learnerId(jwt), id);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal Jwt jwt) {
        service.markAllRead(learnerId(jwt));
    }

    private UUID learnerId(Jwt jwt) {
        return users.provision(jwt.getSubject(), jwt.getClaimAsString("email")).id();
    }

    public record NotificationFeedResponse(
        List<Announcement> items,
        long total,
        int page,
        int size,
        boolean hasMore,
        long unreadCount
    ) {}

    public record Announcement(
        UUID id,
        String title,
        String body,
        String category,
        Instant publishedAt,
        boolean read,
        Instant readAt
    ) {
        static Announcement from(LearnerNotification row) {
            return new Announcement(
                row.id(),
                row.title(),
                row.body(),
                row.category(),
                row.publishedAt(),
                row.read(),
                row.readAt()
            );
        }
    }
}
