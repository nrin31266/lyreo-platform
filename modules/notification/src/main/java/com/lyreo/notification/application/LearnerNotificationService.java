package com.lyreo.notification.application;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.notification.application.port.LearnerNotificationRepository;
import com.lyreo.notification.domain.LearnerNotification;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Service for human-readable learner notifications & platform announcements. */
public class LearnerNotificationService {
    private static final Set<String> CATEGORIES = Set.of("SYSTEM", "STUDY_REMINDER", "REWARD", "PROMO");
    private static final Set<String> AUDIENCES = Set.of("ALL", "FREE", "PLUS");
    private static final Set<String> STATUSES = Set.of("DRAFT", "PUBLISHED", "ARCHIVED");

    private final LearnerNotificationRepository repository;
    private final EntitlementService entitlements;

    public LearnerNotificationService(
        LearnerNotificationRepository repository,
        EntitlementService entitlements
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.entitlements = entitlements;
    }

    @Transactional(readOnly = true)
    public List<LearnerNotification> listAllForAdmin() {
        return repository.listAll();
    }

    @Transactional(readOnly = true)
    public List<LearnerNotification> listForLearner(UUID learnerId) {
        boolean isPlus = false;
        if (entitlements != null && learnerId != null) {
            isPlus = entitlements.listLearnerEntitlements(learnerId).stream()
                .anyMatch(access -> access.granted() && "PLUS".equalsIgnoreCase(access.tier()));
        }
        return repository.listPublishedForAudience(isPlus ? "PLUS" : "FREE");
    }

    @Transactional
    public LearnerNotification create(SaveCommand cmd, String actor) {
        LearnerNotification validated = validate(UUID.randomUUID(), cmd, actor == null || actor.isBlank() ? "admin" : actor);
        return repository.insert(validated);
    }

    @Transactional
    public LearnerNotification update(UUID id, SaveCommand cmd, String actor) {
        LearnerNotification existing = repository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        LearnerNotification validated = validate(
            id,
            cmd,
            existing.createdBy() != null ? existing.createdBy() : (actor == null ? "admin" : actor)
        );
        return repository.update(validated)
            .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.delete(id)) {
            throw new ResourceNotFoundException("Notification not found");
        }
    }

    private static LearnerNotification validate(UUID id, SaveCommand cmd, String actor) {
        if (cmd.title() == null || cmd.title().isBlank() || cmd.title().trim().length() > 200) {
            throw new RequestValidationException("title is required and must be <= 200 characters");
        }
        if (cmd.body() == null || cmd.body().isBlank() || cmd.body().trim().length() > 2000) {
            throw new RequestValidationException("body is required and must be <= 2000 characters");
        }
        String category = (cmd.category() == null || cmd.category().isBlank())
            ? "SYSTEM"
            : cmd.category().trim().toUpperCase(Locale.ROOT);
        if (!CATEGORIES.contains(category)) {
            throw new RequestValidationException("Invalid notification category: " + category);
        }
        String audience = (cmd.targetAudience() == null || cmd.targetAudience().isBlank())
            ? "ALL"
            : cmd.targetAudience().trim().toUpperCase(Locale.ROOT);
        if (!AUDIENCES.contains(audience)) {
            throw new RequestValidationException("Invalid targetAudience: " + audience);
        }
        String status = (cmd.status() == null || cmd.status().isBlank())
            ? "PUBLISHED"
            : cmd.status().trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw new RequestValidationException("Invalid notification status: " + status);
        }
        Instant publishedAt = "PUBLISHED".equals(status)
            ? (cmd.publishedAt() != null ? cmd.publishedAt() : Instant.now())
            : cmd.publishedAt();
        return new LearnerNotification(
            id,
            cmd.title().trim(),
            cmd.body().trim(),
            category,
            audience,
            status,
            publishedAt,
            cmd.expiresAt(),
            actor,
            null,
            null
        );
    }

    public record SaveCommand(
        String title,
        String body,
        String category,
        String targetAudience,
        String status,
        Instant publishedAt,
        Instant expiresAt
    ) {}
}
