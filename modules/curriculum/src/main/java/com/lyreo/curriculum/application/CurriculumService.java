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
import com.lyreo.platform.config.application.ConsumerReceiptStore;
import java.util.ArrayList;
import java.util.HashMap;
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

    public CurriculumService(CurriculumProgressRepository repository) {
        this(repository, null);
    }

    public CurriculumService(CurriculumProgressRepository repository, ConsumerReceiptStore receipts) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.receipts = receipts;
    }

    @Transactional(readOnly = true)
    public List<CurriculumPathSummary> listPublishedPaths(UUID learnerId) {
        return repository.listPublishedPaths(learnerId);
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
        if (learnerId == null || contentType == null || contentReferenceId == null) {
            return;
        }
        if (receipts != null && factId != null && !receipts.tryRecord(CONSUMER_NAME, factId)) {
            return;
        }

        List<CurriculumProgressRepository.ActiveItemTarget> targets =
            repository.activeEnrolledItemTargetsReferencing(learnerId, contentType, contentReferenceId);
        if (targets.isEmpty()) {
            return;
        }

        Set<UUID> affectedPaths = new LinkedHashSet<>();
        for (CurriculumProgressRepository.ActiveItemTarget target : targets) {
            repository.complete(learnerId, target.itemId());
            affectedPaths.add(target.pathId());
        }

        for (UUID pathId : affectedPaths) {
            recomputePathProgress(learnerId, pathId);
        }
    }

    @Transactional
    public void recomputePathProgress(UUID learnerId, UUID pathId) {
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
                    boolean isOpen = isOpenRule(item.unlockRule());
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
                    progress != null ? progress.completedAt() : null
                ));
            }
            sectionViews.add(new CurriculumPathDetail.SectionView(
                section.id(),
                section.title(),
                section.position(),
                itemViews
            ));
        }

        return new CurriculumPathDetail(
            path.id(),
            path.code(),
            path.name(),
            path.level(),
            path.description(),
            path.status(),
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

    private static boolean isOpenRule(String unlockRule) {
        return unlockRule != null
            && ("OPEN".equalsIgnoreCase(unlockRule.trim()) || "FREE".equalsIgnoreCase(unlockRule.trim()));
    }

    private static boolean isActionable(String status) {
        return CurriculumItemProgress.STATUS_IN_PROGRESS.equals(status)
            || CurriculumItemProgress.STATUS_UNLOCKED.equals(status);
    }
}
