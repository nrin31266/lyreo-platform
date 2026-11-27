package com.lyreo.curriculum.application.port;

import com.lyreo.curriculum.domain.CurriculumEnrollment;
import com.lyreo.curriculum.domain.CurriculumItem;
import com.lyreo.curriculum.domain.CurriculumItem.ContentType;
import com.lyreo.curriculum.domain.CurriculumItemProgress;
import com.lyreo.curriculum.domain.CurriculumPath;
import com.lyreo.curriculum.domain.CurriculumPathSummary;
import com.lyreo.curriculum.domain.CurriculumSection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface CurriculumProgressRepository {
    List<UUID> itemIdsReferencing(ContentType type, UUID contentReferenceId);

    List<ActiveItemTarget> activeEnrolledItemTargetsReferencing(
        UUID learnerId,
        ContentType type,
        UUID contentReferenceId
    );

    void complete(UUID learnerId, UUID itemId);

    void upsertItemProgressStatus(UUID learnerId, UUID itemId, String status);

    Optional<CurriculumPath> findPathById(UUID pathId);

    default Optional<UUID> findPublishedCoverAssetId(UUID pathId) {
        return findPathById(pathId)
            .filter(CurriculumPath::isPublished)
            .map(CurriculumPath::coverAssetId);
    }

    List<CurriculumPathSummary> listPublishedPaths(UUID learnerId);

    List<CurriculumPath> listAllPaths();

    List<CurriculumSection> findSectionsByPathId(UUID pathId);

    List<CurriculumItem> findItemsByPathId(UUID pathId);

    Optional<CurriculumEnrollment> findEnrollment(UUID learnerId, UUID pathId);

    List<CurriculumEnrollment> findActivePublishedEnrollments(UUID learnerId);

    List<CurriculumItemProgress> findItemProgressByPath(UUID learnerId, UUID pathId);

    void ensureActiveEnrollment(UUID learnerId, UUID pathId);

    void markEnrollmentCompleted(UUID learnerId, UUID pathId);

    default CurriculumPath insertPath(UUID id, String code, String name, String level, String description, String status) {
        return insertPath(id, code, name, level, description, status, null, "PUBLIC", null);
    }

    CurriculumPath insertPath(
        UUID id,
        String code,
        String name,
        String level,
        String description,
        String status,
        UUID coverAssetId,
        String accessMode,
        String requiredFeatureKey
    );

    default Optional<CurriculumPath> updatePath(UUID id, String code, String name, String level, String description) {
        return updatePath(id, code, name, level, description, null, null, null);
    }

    Optional<CurriculumPath> updatePath(
        UUID id,
        String code,
        String name,
        String level,
        String description,
        UUID coverAssetId,
        String accessMode,
        String requiredFeatureKey
    );

    Optional<CurriculumPath> updatePathStatus(UUID id, String status);

    boolean deletePath(UUID id);

    default Map<UUID, String> resolveContentTitles(List<CurriculumItem> items) {
        return Map.of();
    }

    CurriculumSection insertSection(UUID id, UUID pathId, String title, int position);

    CurriculumItem insertItem(
        UUID id,
        UUID sectionId,
        int position,
        ContentType contentType,
        UUID contentReferenceId,
        boolean required,
        String unlockRule,
        String configJson
    );

    void deleteSectionsByPathId(UUID pathId);

    Optional<CurriculumSection> findSectionById(UUID sectionId);

    boolean existsByCode(String code, UUID excludePathId);

    record ActiveItemTarget(UUID itemId, UUID pathId) {}
}
