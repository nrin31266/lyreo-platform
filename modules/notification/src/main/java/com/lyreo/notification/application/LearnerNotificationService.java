package com.lyreo.notification.application;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.notification.application.port.LearnerNotificationRepository;
import com.lyreo.notification.domain.AdminActivity;
import com.lyreo.notification.domain.LearnerNotification;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Service for human-readable learner notifications & platform announcements. */
public class LearnerNotificationService {
    private static final Set<String> CATEGORIES = Set.of("SYSTEM", "STUDY_REMINDER", "REWARD", "PROMO");
    private static final Set<String> AUDIENCES = Set.of("ALL", "FREE", "PLUS", "SELECTED");
    private static final Set<String> STATUSES = Set.of("DRAFT", "PUBLISHED", "ARCHIVED");
    private static final Set<String> ACTIVITY_CATEGORIES = Set.of("MEMBERSHIP", "ACCOUNT", "NOTIFICATION", "REWARD", "OTHER");
    private static final int ACTIVITY_PAGE_SIZE = 30;

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
        String tier = resolveLearnerTier(learnerId);
        return repository.listPublishedForAudience(learnerId, tier);
    }

    @Transactional(readOnly = true)
    public NotificationPage listForLearnerPage(UUID learnerId, int page, int size, boolean unreadOnly) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) {
            throw new RequestValidationException("Invalid notification page or size");
        }
        String tier = resolveLearnerTier(learnerId);
        long total = repository.countPublishedForAudience(learnerId, tier, unreadOnly);
        long unreadCount = repository.countUnreadForAudience(learnerId, tier);
        List<LearnerNotification> items = repository.listPublishedForAudience(
            learnerId, tier, unreadOnly, page * size, size);
        boolean hasMore = (long) (page + 1) * size < total;
        return new NotificationPage(items, total, page, size, hasMore, unreadCount);
    }

    @Transactional
    public void markRead(UUID learnerId, UUID notificationId) {
        Objects.requireNonNull(learnerId, "learnerId");
        Objects.requireNonNull(notificationId, "notificationId");
        String tier = resolveLearnerTier(learnerId);
        if (!repository.markRead(learnerId, tier, notificationId)) {
            throw new ResourceNotFoundException("Notification not found");
        }
    }

    @Transactional
    public int markAllRead(UUID learnerId) {
        Objects.requireNonNull(learnerId, "learnerId");
        return repository.markAllRead(learnerId, resolveLearnerTier(learnerId));
    }

    private String resolveLearnerTier(UUID learnerId) {
        boolean isPlus = entitlements != null && learnerId != null && entitlements.isPlus(learnerId);
        return isPlus ? "PLUS" : "FREE";
    }

    public record NotificationPage(
        List<LearnerNotification> items,
        long total,
        int page,
        int size,
        boolean hasMore,
        long unreadCount
    ) {}

    @Transactional(readOnly = true)
    public ActivityPage listActivity(String category, int page) {
        if (page < 0 || page > Integer.MAX_VALUE / ACTIVITY_PAGE_SIZE ||
            (category != null && !ACTIVITY_CATEGORIES.contains(category))) {
            throw new RequestValidationException("Invalid activity category or page");
        }
        List<AdminActivity> rows = repository.listActivity(category, page * ACTIVITY_PAGE_SIZE, ACTIVITY_PAGE_SIZE + 1);
        boolean hasNext = rows.size() > ACTIVITY_PAGE_SIZE;
        return new ActivityPage(hasNext ? rows.subList(0, ACTIVITY_PAGE_SIZE) : rows, page, hasNext);
    }

    @Transactional
    public LearnerNotification create(SaveCommand cmd, String actor) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        LearnerNotification created = repository.insert(validate(UUID.randomUUID(), cmd, actor, null, now));
        repository.audit(created, actor, activityAction(null, created, now));
        return created;
    }

    @Transactional
    public LearnerNotification update(UUID id, SaveCommand cmd, String actor) {
        LearnerNotification existing = repository.findByIdForUpdate(id)
            .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        LearnerNotification validated = validate(id, cmd, existing.createdBy(), existing, now);
        LearnerNotification updated = repository.update(validated)
            .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        repository.audit(updated, actor, activityAction(existing, updated, now));
        return updated;
    }

    private static String activityAction(LearnerNotification previous, LearnerNotification saved, Instant now) {
        if ("PUBLISHED".equals(saved.status())) {
            boolean newPublication = previous == null || !"PUBLISHED".equals(previous.status());
            boolean rescheduled = previous != null && previous.publishedAt() != null && previous.publishedAt().isAfter(now)
                && !previous.publishedAt().equals(saved.publishedAt());
            if (newPublication || rescheduled) {
                return saved.publishedAt().isAfter(now) ? "NOTIFICATION_SCHEDULED" : "NOTIFICATION_PUBLISHED";
            }
        }
        if (previous == null) return "NOTIFICATION_CREATED";
        if ("ARCHIVED".equals(saved.status()) && !"ARCHIVED".equals(previous.status())) return "NOTIFICATION_ARCHIVED";
        return "NOTIFICATION_UPDATED";
    }

    @Transactional
    public void delete(UUID id, String actor) {
        LearnerNotification existing = repository.findByIdForUpdate(id)
            .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        repository.delete(id);
        repository.audit(existing, actor, "NOTIFICATION_DELETED");
    }

    private LearnerNotification validate(UUID id, SaveCommand cmd, String actor, LearnerNotification existing, Instant now) {
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
        boolean explicitAudience = cmd.targetAudience() != null && !cmd.targetAudience().isBlank();
        String audience = !explicitAudience
            ? (existing == null ? "ALL" : existing.targetAudience())
            : cmd.targetAudience().trim().toUpperCase(Locale.ROOT);
        if (!AUDIENCES.contains(audience)) {
            throw new RequestValidationException("Invalid targetAudience: " + audience);
        }
        String status = (cmd.status() == null || cmd.status().isBlank())
            ? (existing == null ? "DRAFT" : existing.status())
            : cmd.status().trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw new RequestValidationException("Invalid notification status: " + status);
        }
        boolean alreadyPublished = existing != null && "PUBLISHED".equals(existing.status());
        if ("PUBLISHED".equals(status) && !alreadyPublished && !explicitAudience) {
            throw new RequestValidationException("Choose an audience before publishing");
        }
        List<UUID> recipients = cmd.recipientIds() != null ? cmd.recipientIds()
            : existing != null && audience.equals(existing.targetAudience()) ? existing.recipientIds() : List.of();
        if (recipients.stream().anyMatch(Objects::isNull) || recipients.size() > 100) {
            throw new RequestValidationException("Select up to 100 valid learners");
        }
        recipients = recipients.stream().distinct().sorted().toList();
        if (("SELECTED".equals(audience) != !recipients.isEmpty()) ||
            (!recipients.isEmpty() && !repository.recipientsExist(recipients))) {
            throw new RequestValidationException("Select valid learners for the selected audience");
        }
        if ("STUDY_REMINDER".equals(category) && "ALL".equals(audience) && "PUBLISHED".equals(status)) {
            throw new RequestValidationException("Choose a group or specific learners for a study reminder");
        }
        Instant previousPublication = existing == null ? null : existing.publishedAt();
        if (previousPublication == null && alreadyPublished) previousPublication = existing.createdAt();
        boolean audienceLocked = previousPublication != null && !previousPublication.isAfter(now);
        if (audienceLocked &&
            (!audience.equals(existing.targetAudience()) || !Set.copyOf(recipients).equals(Set.copyOf(existing.recipientIds())))) {
            throw new StateConflictException("Create a new notification to change the audience after publication");
        }
        Instant publishedAt = previousPublication;
        if (!audienceLocked) {
            publishedAt = null;
            if ("PUBLISHED".equals(status)) {
                publishedAt = cmd.publishedAt() != null ? cmd.publishedAt() : Objects.requireNonNullElse(previousPublication, now);
            }
        }
        if (publishedAt != null && cmd.expiresAt() != null && !cmd.expiresAt().isAfter(publishedAt)) {
            throw new RequestValidationException("expiresAt must be after publishedAt");
        }
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
            null,
            recipients,
            List.of()
        );
    }

    public record SaveCommand(
        String title,
        String body,
        String category,
        String targetAudience,
        String status,
        Instant publishedAt,
        Instant expiresAt,
        List<UUID> recipientIds
    ) {}

    public record ActivityPage(List<AdminActivity> items, int page, boolean hasNext) {}
}
