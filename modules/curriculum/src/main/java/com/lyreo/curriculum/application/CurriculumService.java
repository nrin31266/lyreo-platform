package com.lyreo.curriculum.application;

import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.curriculum.application.port.CurriculumProgressRepository;
import com.lyreo.curriculum.domain.ContinueLearningView;
import com.lyreo.curriculum.domain.CurriculumEnrollment;
import com.lyreo.curriculum.domain.CurriculumItem;
import com.lyreo.curriculum.domain.CurriculumItem.ContentType;
import com.lyreo.curriculum.domain.CurriculumItemProgress;
import com.lyreo.curriculum.domain.CurriculumPath;
import com.lyreo.curriculum.domain.CurriculumPathDetail;
import com.lyreo.curriculum.domain.CurriculumPathSummary;
import com.lyreo.curriculum.domain.CurriculumSection;
import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.platform.config.application.ConsumerReceiptStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Learner-facing curriculum progression, enrollment, unlock engine, and continue-learning service. */
public class CurriculumService {
    private static final String CONSUMER_NAME = "curriculum.completion";

    private final CurriculumProgressRepository repository;
    private final ConsumerReceiptStore receipts;
    private final EntitlementService entitlements;

    public CurriculumService(CurriculumProgressRepository repository) {
        this(repository, null, null);
    }

    public CurriculumService(CurriculumProgressRepository repository, ConsumerReceiptStore receipts) {
        this(repository, receipts, null);
    }

    public CurriculumService(
        CurriculumProgressRepository repository,
        ConsumerReceiptStore receipts,
        EntitlementService entitlements
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.receipts = receipts;
        this.entitlements = entitlements;
    }

    @Transactional(readOnly = true)
    public List<CurriculumPathSummary> listPublishedPaths(UUID learnerId) {
        List<CurriculumPathSummary> paths = repository.listPublishedPaths(learnerId);
        if (entitlements == null || learnerId == null) {
            return paths;
        }
        Map<String, Boolean> lockedCache = new HashMap<>();
        return paths.stream()
            .map(summary -> {
                if (!"FEATURE".equals(summary.accessMode()) || summary.requiredFeatureKey() == null
                    || summary.requiredFeatureKey().isBlank()) {
                    return summary;
                }
                boolean locked = lockedCache.computeIfAbsent(
                    summary.requiredFeatureKey(),
                    key -> isLocked(learnerId, summary.accessMode(), key)
                );
                return summary.withLocked(locked);
            })
            .toList();
    }

    @Transactional(readOnly = true)
    public UUID getPublishedCoverAssetId(UUID pathId) {
        return repository.findPublishedCoverAssetId(pathId)
            .orElseThrow(() -> new ResourceNotFoundException("Curriculum cover image not found"));
    }

    @Transactional(readOnly = true)
    public CurriculumPathDetail getPublishedPathDetail(UUID learnerId, UUID pathId) {
        CurriculumPath path = repository.findPathById(pathId)
            .filter(CurriculumPath::isPublished)
            .orElseThrow(() -> new ResourceNotFoundException("Curriculum path not found"));
        return buildPathDetail(learnerId, path);
    }

    @Transactional
    public CurriculumPathDetail enroll(UUID learnerId, UUID pathId) {
        Objects.requireNonNull(learnerId, "learnerId");
        Objects.requireNonNull(pathId, "pathId");

        CurriculumPath path = repository.findPathById(pathId)
            .orElseThrow(() -> new ResourceNotFoundException("Curriculum path not found"));
        if (!path.isPublished()) {
            throw new StateConflictException("Curriculum path is not published");
        }
        if (entitlements != null && "FEATURE".equals(path.accessMode()) && path.requiredFeatureKey() != null) {
            entitlements.requireFeature(learnerId, FeatureKey.of(path.requiredFeatureKey()));
        }

        repository.ensureActiveEnrollment(learnerId, pathId);
        recomputePathProgress(learnerId, pathId);
        return buildPathDetail(learnerId, path);
    }

    @Transactional(readOnly = true)
    public ContinueLearningView continueLearning(UUID learnerId) {
        Objects.requireNonNull(learnerId, "learnerId");
        List<CurriculumEnrollment> enrollments = repository.findActivePublishedEnrollments(learnerId);
        for (CurriculumEnrollment enrollment : enrollments) {
            CurriculumPath path = repository.findPathById(enrollment.pathId()).orElse(null);
            if (path == null || !path.isPublished()) {
                continue;
            }
            CurriculumPathDetail detail = buildPathDetail(learnerId, path);
            CurriculumPathDetail.SectionView matchedSection = null;
            CurriculumPathDetail.ItemView matchedItem = null;

            for (var section : detail.sections()) {
                for (var item : section.items()) {
                    if (isActionable(item.progressStatus()) && item.required()) {
                        matchedSection = section;
                        matchedItem = item;
                        break;
                    }
                }
                if (matchedItem != null) {
                    break;
                }
            }

            if (matchedItem == null) {
                for (var section : detail.sections()) {
                    for (var item : section.items()) {
                        if (isActionable(item.progressStatus())) {
                            matchedSection = section;
                            matchedItem = item;
                            break;
                        }
                    }
                    if (matchedItem != null) {
                        break;
                    }
                }
            }

            if (matchedItem != null) {
                return new ContinueLearningView(
                    true,
                    path.id(),
                    path.code(),
                    path.name(),
                    matchedSection.id(),
                    matchedSection.title(),
                    matchedItem.id(),
                    matchedItem.contentType(),
                    matchedItem.contentReferenceId(),
                    matchedItem.contentTitle(),
                    matchedItem.config(),
                    detail.coverAssetId(),
                    detail.coverUrl(),
                    matchedItem.progressStatus(),
                    detail.completedRequiredItems(),
                    detail.totalRequiredItems()
                );
            }
        }
        return ContinueLearningView.empty();
    }

    @Transactional
    public void onContentCompleted(
        UUID factId,
        UUID learnerId,
        ContentType contentType,
        UUID contentReferenceId
    ) {
        onContentCompleted(factId, learnerId, contentType, contentReferenceId, CompletionEvidence.empty());
    }

    @Transactional
    public void onContentCompleted(
        UUID factId,
        UUID learnerId,
        ContentType contentType,
        UUID contentReferenceId,
        CompletionEvidence evidence
    ) {
        if (learnerId == null || contentType == null || contentReferenceId == null) {
            return;
        }
        if (receipts != null && factId != null && !receipts.tryRecord(CONSUMER_NAME, factId)) {
            return;
        }

        CompletionEvidence resolvedEvidence = evidence == null ? CompletionEvidence.empty() : evidence;
        Set<UUID> referenceIds = new LinkedHashSet<>();
        referenceIds.add(contentReferenceId);
        referenceIds.addAll(resolvedEvidence.alternateReferenceIds());

        Map<UUID, CurriculumProgressRepository.ActiveItemTarget> uniqueTargets = new LinkedHashMap<>();
        for (UUID refId : referenceIds) {
            for (CurriculumProgressRepository.ActiveItemTarget target :
                repository.activeEnrolledItemTargetsReferencing(learnerId, contentType, refId)) {
                uniqueTargets.putIfAbsent(target.itemId(), target);
            }
        }
        if (uniqueTargets.isEmpty()) {
            return;
        }

        Map<UUID, List<CurriculumProgressRepository.ActiveItemTarget>> byPath = new LinkedHashMap<>();
        for (CurriculumProgressRepository.ActiveItemTarget target : uniqueTargets.values()) {
            byPath.computeIfAbsent(target.pathId(), ignored -> new ArrayList<>()).add(target);
        }

        Set<UUID> affectedPaths = new LinkedHashSet<>();
        for (Map.Entry<UUID, List<CurriculumProgressRepository.ActiveItemTarget>> entry : byPath.entrySet()) {
            UUID pathId = entry.getKey();
            Map<UUID, String> statusByItem = new HashMap<>();
            for (CurriculumItemProgress p : repository.findItemProgressByPath(learnerId, pathId)) {
                statusByItem.put(p.itemId(), p.status());
            }

            for (CurriculumProgressRepository.ActiveItemTarget target : entry.getValue()) {
                String currentStatus = statusByItem.get(target.itemId());
                if (!isActionable(currentStatus)) {
                    continue;
                }
                if (!matchesConfig(target.config(), contentType, resolvedEvidence)) {
                    continue;
                }
                repository.complete(learnerId, target.itemId());
                affectedPaths.add(pathId);
            }
        }

        for (UUID pathId : affectedPaths) {
            recomputePathProgress(learnerId, pathId);
        }
    }

    static boolean matchesConfig(
        Map<String, Object> config,
        ContentType contentType,
        CompletionEvidence evidence
    ) {
        if (config == null || config.isEmpty()) {
            return contentType != ContentType.LESSON || evidence.activityType() == null;
        }

        return switch (contentType) {
            case LESSON -> {
                String expectedActivity = asNonBlankString(config.get("activityType"));
                Set<UUID> expectedSentences = asUuidSet(config.get("sentenceIds"));
                if (expectedActivity == null && expectedSentences.isEmpty()) {
                    yield evidence.activityType() == null;
                }
                if (expectedActivity != null
                    && (evidence.activityType() == null
                        || !expectedActivity.equalsIgnoreCase(evidence.activityType()))) {
                    yield false;
                }
                if (!expectedSentences.isEmpty()) {
                    yield evidence.sentenceIds().containsAll(expectedSentences);
                }
                yield evidence.activityCompleted();
            }
            case VOCABULARY_REVIEW -> {
                Set<UUID> expectedEntries = asUuidSet(config.get("entryIds"));
                yield expectedEntries.isEmpty() || evidence.entryIds().containsAll(expectedEntries);
            }
            case GRAMMAR_PRACTICE -> {
                Set<UUID> expectedSubtopics = asUuidSet(config.get("subtopicIds"));
                if (!expectedSubtopics.isEmpty()) {
                    boolean matchesSubtopic = !evidence.subtopicIds().isEmpty()
                        && evidence.subtopicIds().stream().anyMatch(expectedSubtopics::contains);
                    if (!matchesSubtopic) {
                        yield false;
                    }
                }
                Integer expectedCount = asInteger(config.get("questionCount"));
                if (expectedCount != null && expectedCount > 0
                    && (evidence.questionCount() == null || evidence.questionCount() < expectedCount)) {
                    yield false;
                }
                yield true;
            }
            case TOEIC_DRILL, TOEIC_TEST -> {
                Set<Integer> expectedParts = asIntSet(config.get("parts"));
                if (!expectedParts.isEmpty() && !evidence.parts().containsAll(expectedParts)) {
                    yield false;
                }
                Integer expectedCount = asInteger(config.get("questionCount"));
                if (expectedCount != null && expectedCount > 0
                    && (evidence.questionCount() == null || evidence.questionCount() < expectedCount)) {
                    yield false;
                }
                yield true;
            }
        };
    }

    private static String asNonBlankString(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Set<UUID> asUuidSet(Object raw) {
        if (!(raw instanceof Collection<?> items) || items.isEmpty()) {
            return Set.of();
        }
        Set<UUID> result = new HashSet<>();
        for (Object item : items) {
            if (item == null) {
                continue;
            }
            try {
                result.add(item instanceof UUID uuid ? uuid : UUID.fromString(item.toString().trim()));
            } catch (IllegalArgumentException ignored) {
                // ignore malformed entry
            }
        }
        return result;
    }

    private static Set<Integer> asIntSet(Object raw) {
        if (!(raw instanceof Collection<?> items) || items.isEmpty()) {
            return Set.of();
        }
        Set<Integer> result = new HashSet<>();
        for (Object item : items) {
            Integer parsed = asInteger(item);
            if (parsed != null) {
                result.add(parsed);
            }
        }
        return result;
    }

    public record CompletionEvidence(
        String activityType,
        Set<UUID> entryIds,
        Set<UUID> sentenceIds,
        Set<UUID> subtopicIds,
        Set<Integer> parts,
        Integer questionCount,
        Set<UUID> alternateReferenceIds,
        boolean activityCompleted
    ) {
        public CompletionEvidence(
            String activityType,
            Set<UUID> entryIds,
            Set<UUID> sentenceIds,
            Set<UUID> subtopicIds,
            Set<Integer> parts,
            Integer questionCount,
            Set<UUID> alternateReferenceIds
        ) {
            this(activityType, entryIds, sentenceIds, subtopicIds, parts, questionCount, alternateReferenceIds, true);
        }

        public CompletionEvidence {
            entryIds = entryIds == null ? Set.of() : Set.copyOf(entryIds);
            sentenceIds = sentenceIds == null ? Set.of() : Set.copyOf(sentenceIds);
            subtopicIds = subtopicIds == null ? Set.of() : Set.copyOf(subtopicIds);
            parts = parts == null ? Set.of() : Set.copyOf(parts);
            alternateReferenceIds = alternateReferenceIds == null ? Set.of() : Set.copyOf(alternateReferenceIds);
        }

        public static CompletionEvidence empty() {
            return new CompletionEvidence(null, Set.of(), Set.of(), Set.of(), Set.of(), null, Set.of(), true);
        }
    }

    @Transactional
    public void recomputePathProgress(UUID learnerId, UUID pathId) {
        recomputePathProgress(learnerId, pathId, false);
    }

    void recomputeAfterPathEdit(UUID learnerId, UUID pathId) {
        recomputePathProgress(learnerId, pathId, true);
    }

    private void recomputePathProgress(UUID learnerId, UUID pathId, boolean pathEdited) {
        List<CurriculumSection> sections = repository.findSectionsByPathId(pathId);
        List<CurriculumItem> items = repository.findItemsByPathId(pathId);
        if (items.isEmpty()) {
            return;
        }

        Map<UUID, CurriculumItemProgress> progressByItem = new HashMap<>();
        for (CurriculumItemProgress p : repository.findItemProgressByPath(learnerId, pathId)) {
            progressByItem.put(p.itemId(), p);
        }

        Map<UUID, List<CurriculumItem>> itemsBySection = new HashMap<>();
        for (CurriculumItem item : items) {
            itemsBySection.computeIfAbsent(item.sectionId(), ignored -> new ArrayList<>()).add(item);
        }

        boolean previousRequiredCompleted = true;
        int requiredCount = 0;
        int completedRequiredCount = 0;
        int completedTotalCount = 0;

        for (CurriculumSection section : sections) {
            List<CurriculumItem> sectionItems = itemsBySection.getOrDefault(section.id(), List.of());
            for (CurriculumItem item : sectionItems) {
                CurriculumItemProgress existing = progressByItem.get(item.id());
                String currentStatus = existing != null
                    ? existing.status()
                    : CurriculumItemProgress.STATUS_LOCKED;
                boolean isCompleted = CurriculumItemProgress.STATUS_COMPLETED.equals(currentStatus);

                if (item.required()) {
                    requiredCount++;
                    if (isCompleted) {
                        completedRequiredCount++;
                    }
                }
                if (isCompleted) {
                    completedTotalCount++;
                }

                if (!isCompleted && !CurriculumItemProgress.STATUS_IN_PROGRESS.equals(currentStatus)) {
                    boolean isOpen = isOpenRule(item.unlockRule())
                        && (item.required() || !"NONE".equalsIgnoreCase(item.unlockRule().trim()));
                    String desiredStatus = (previousRequiredCompleted || isOpen)
                        ? CurriculumItemProgress.STATUS_UNLOCKED
                        : CurriculumItemProgress.STATUS_LOCKED;
                    if (existing == null || !desiredStatus.equals(currentStatus)) {
                        repository.upsertItemProgressStatus(learnerId, item.id(), desiredStatus);
                    }
                }

                if (item.required() && !isCompleted) {
                    previousRequiredCompleted = false;
                }
            }
        }

        boolean pathCompleted = requiredCount > 0
            ? completedRequiredCount == requiredCount
            : completedTotalCount == items.size();
        if (pathCompleted) {
            repository.markEnrollmentCompleted(learnerId, pathId);
        } else if (pathEdited) {
            repository.reopenCompletedEnrollment(learnerId, pathId);
        }
    }

    CurriculumPathDetail buildPathDetail(UUID learnerId, CurriculumPath path) {
        CurriculumEnrollment enrollment = learnerId != null
            ? repository.findEnrollment(learnerId, path.id()).orElse(null)
            : null;
        List<CurriculumSection> sections = repository.findSectionsByPathId(path.id());
        List<CurriculumItem> items = repository.findItemsByPathId(path.id());
        Map<UUID, CurriculumItemProgress> progressByItem = new HashMap<>();
        if (learnerId != null) {
            for (CurriculumItemProgress p : repository.findItemProgressByPath(learnerId, path.id())) {
                progressByItem.put(p.itemId(), p);
            }
        }

        Map<UUID, List<CurriculumItem>> itemsBySection = new HashMap<>();
        for (CurriculumItem item : items) {
            itemsBySection.computeIfAbsent(item.sectionId(), ignored -> new ArrayList<>()).add(item);
        }

        Map<UUID, String> contentTitles = repository.resolveContentTitles(items);

        int totalRequired = 0;
        int completedRequired = 0;
        List<CurriculumPathDetail.SectionView> sectionViews = new ArrayList<>();
        for (CurriculumSection section : sections) {
            List<CurriculumPathDetail.ItemView> itemViews = new ArrayList<>();
            for (CurriculumItem item : itemsBySection.getOrDefault(section.id(), List.of())) {
                CurriculumItemProgress progress = progressByItem.get(item.id());
                String itemStatus = progress != null
                    ? progress.status()
                    : CurriculumItemProgress.STATUS_LOCKED;
                if (item.required()) {
                    totalRequired++;
                    if (CurriculumItemProgress.STATUS_COMPLETED.equals(itemStatus)) {
                        completedRequired++;
                    }
                }
                itemViews.add(new CurriculumPathDetail.ItemView(
                    item.id(),
                    item.position(),
                    item.contentType(),
                    item.contentReferenceId(),
                    item.required(),
                    item.unlockRule(),
                    itemStatus,
                    progress != null ? progress.completedAt() : null,
                    item.config(),
                    contentTitles.get(item.contentReferenceId())
                ));
            }
            sectionViews.add(new CurriculumPathDetail.SectionView(
                section.id(),
                section.title(),
                section.position(),
                itemViews
            ));
        }

        boolean locked = learnerId != null && isLocked(learnerId, path.accessMode(), path.requiredFeatureKey());
        return new CurriculumPathDetail(
            path.id(),
            path.code(),
            path.name(),
            path.level(),
            path.description(),
            path.status(),
            path.coverAssetId(),
            path.accessMode(),
            path.requiredFeatureKey(),
            locked,
            path.createdAt(),
            path.updatedAt(),
            enrollment != null ? enrollment.status() : null,
            enrollment != null ? enrollment.startedAt() : null,
            enrollment != null ? enrollment.completedAt() : null,
            completedRequired,
            totalRequired,
            sectionViews
        );
    }

    private boolean isLocked(UUID learnerId, String accessMode, String requiredFeatureKey) {
        if (entitlements == null || !"FEATURE".equals(accessMode) || requiredFeatureKey == null || requiredFeatureKey.isBlank()) {
            return false;
        }
        return !entitlements.hasFeature(learnerId, FeatureKey.of(requiredFeatureKey));
    }

    private static boolean isOpenRule(String unlockRule) {
        if (unlockRule == null) {
            return false;
        }
        String normalized = unlockRule.trim();
        return "NONE".equalsIgnoreCase(normalized)
            || "OPEN".equalsIgnoreCase(normalized)
            || "FREE".equalsIgnoreCase(normalized);
    }

    private static boolean isActionable(String status) {
        return CurriculumItemProgress.STATUS_IN_PROGRESS.equals(status)
            || CurriculumItemProgress.STATUS_UNLOCKED.equals(status);
    }
}
