package com.lyreo.notification.api;

import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.notification.application.LearnerNotificationService;
import com.lyreo.notification.domain.LearnerNotification;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Learner-facing feed of published announcements & study reminders. */
@RestController
@RequestMapping("/api/v1/notifications")
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
    public List<LearnerNotification> feed(@AuthenticationPrincipal Jwt jwt) {
        UUID learnerId = users.provision(jwt.getSubject(), jwt.getClaimAsString("email")).id();
        return service.listForLearner(learnerId);
    }
}
