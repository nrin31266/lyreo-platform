package com.lyreo.curriculum.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lyreo.curriculum.application.port.CurriculumProgressRepository;
import com.lyreo.curriculum.domain.CurriculumItem;
import com.lyreo.curriculum.domain.CurriculumPath;
import com.lyreo.curriculum.domain.CurriculumPathDetail;
import com.lyreo.curriculum.domain.CurriculumSection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CurriculumAdminServiceTest {

    private CurriculumProgressRepository repository;
    private CurriculumService curriculumService;
    private CurriculumAdminService adminService;

    @BeforeEach
    void setUp() {
        repository = mock(CurriculumProgressRepository.class);
        curriculumService = mock(CurriculumService.class);
        adminService = new CurriculumAdminService(repository, curriculumService);
    }

    @Test
    void createPathAutoGeneratesUniqueCodeFromVietnameseName() {
        when(repository.existsByCode("LO_TRINH_TOEIC_500", null)).thenReturn(true);
        when(repository.existsByCode("LO_TRINH_TOEIC_500_2", null)).thenReturn(false);

        CurriculumPath saved = new CurriculumPath(
            UUID.randomUUID(),
            "LO_TRINH_TOEIC_500_2",
            "Lộ trình TOEIC 500+",
            "A2",
            null,
            "DRAFT",
            null,
            "PUBLIC",
            null,
            Instant.now(),
            Instant.now()
        );
        when(repository.insertPath(any(), eq("LO_TRINH_TOEIC_500_2"), eq("Lộ trình TOEIC 500+"),
            eq("A2"), isNull(), eq("DRAFT"), isNull(), eq("PUBLIC"), isNull())).thenReturn(saved);
        CurriculumPathDetail detail = new CurriculumPathDetail(
            saved.id(), saved.code(), saved.name(), saved.level(), saved.description(),
            saved.status(), saved.coverAssetId(), saved.accessMode(), saved.requiredFeatureKey(),
            false, saved.createdAt(), saved.updatedAt(), null, null, null, 0, 0, List.of()
        );
        when(curriculumService.buildPathDetail(isNull(), eq(saved))).thenReturn(detail);

        CurriculumPathDetail result = adminService.createPath(new CurriculumAdminService.SavePathCommand(
            null,
            "Lộ trình TOEIC 500+",
            "A2",
            null,
            "DRAFT",
            null,
            "PUBLIC",
            null,
            List.of()
        ));

        assertThat(result.code()).isEqualTo("LO_TRINH_TOEIC_500_2");
        verify(repository).existsByCode("LO_TRINH_TOEIC_500", null);
        verify(repository).existsByCode("LO_TRINH_TOEIC_500_2", null);
    }

    @Test
    void updatePathPassesNullCoverAssetIdToClearExistingCover() {
        UUID pathId = UUID.randomUUID();
        UUID oldCoverId = UUID.randomUUID();
        CurriculumPath existing = new CurriculumPath(
            pathId,
            "EXISTING_PATH",
            "Existing Path",
            "B1",
            "Desc",
            "PUBLISHED",
            oldCoverId,
            "PUBLIC",
            null,
            Instant.now(),
            Instant.now()
        );
        CurriculumPath updated = new CurriculumPath(
            pathId,
            "EXISTING_PATH",
            "Existing Path",
            "B1",
            "Desc",
            "PUBLISHED",
            null,
            "PUBLIC",
            null,
            existing.createdAt(),
            Instant.now()
        );
        when(repository.findPathById(pathId)).thenReturn(Optional.of(existing));
        when(repository.existsByCode("EXISTING_PATH", pathId)).thenReturn(false);
        when(repository.updatePath(pathId, "EXISTING_PATH", "Existing Path", "B1", "Desc", null, "PUBLIC", null))
            .thenReturn(Optional.of(updated));
        CurriculumPathDetail detail = new CurriculumPathDetail(
            pathId, "EXISTING_PATH", "Existing Path", "B1", "Desc", "PUBLISHED",
            null, "PUBLIC", null, false, updated.createdAt(), updated.updatedAt(),
            null, null, null, 0, 0, List.of()
        );
        when(curriculumService.buildPathDetail(isNull(), eq(updated))).thenReturn(detail);

        CurriculumPathDetail result = adminService.updatePath(pathId, new CurriculumAdminService.SavePathCommand(
            "EXISTING_PATH",
            "Existing Path",
            "B1",
            "Desc",
            "PUBLISHED",
            null,
            "PUBLIC",
            null,
            null
        ));

        assertThat(result.coverAssetId()).isNull();
        verify(repository).updatePath(pathId, "EXISTING_PATH", "Existing Path", "B1", "Desc", null, "PUBLIC", null);
    }

    @Test
    void updatePathCanClearLevelAndDescriptionAndNormalizesOptionalNoneRule() {
        UUID pathId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        UUID refId = UUID.randomUUID();
        CurriculumPath existing = new CurriculumPath(
            pathId,
            "PATH_CODE",
            "Path Name",
            "B1",
            "Old description",
            "DRAFT",
            null,
            "PUBLIC",
            null,
            Instant.now(),
            Instant.now()
        );
        CurriculumPath updated = new CurriculumPath(
            pathId,
            "PATH_CODE",
            "Path Name",
            null,
            null,
            "DRAFT",
            null,
            "PUBLIC",
            null,
            existing.createdAt(),
            Instant.now()
        );
        when(repository.findPathById(pathId)).thenReturn(Optional.of(existing));
        when(repository.existsByCode("PATH_CODE", pathId)).thenReturn(false);
        when(repository.updatePath(pathId, "PATH_CODE", "Path Name", null, null, null, "PUBLIC", null))
            .thenReturn(Optional.of(updated));
        when(repository.insertSection(any(), eq(pathId), eq("Chapter 1"), eq(1)))
            .thenReturn(new CurriculumSection(sectionId, pathId, "Chapter 1", 1));
        when(repository.insertItem(any(), any(), eq(1), eq(CurriculumItem.ContentType.LESSON), eq(refId), eq(false), eq("PREVIOUS_REQUIRED"), eq("{}")))
            .thenReturn(new CurriculumItem(UUID.randomUUID(), sectionId, 1, CurriculumItem.ContentType.LESSON, refId, false, "PREVIOUS_REQUIRED", java.util.Map.of()));
        when(curriculumService.buildPathDetail(isNull(), eq(updated))).thenReturn(new CurriculumPathDetail(
            pathId, "PATH_CODE", "Path Name", null, null, "DRAFT",
            null, "PUBLIC", null, false, updated.createdAt(), updated.updatedAt(),
            null, null, null, 0, 0, List.of()
        ));

        CurriculumPathDetail result = adminService.updatePath(pathId, new CurriculumAdminService.SavePathCommand(
            "PATH_CODE",
            "Path Name",
            "  ",
            "",
            "DRAFT",
            null,
            "PUBLIC",
            null,
            List.of(new CurriculumAdminService.SectionSpec(
                "Chapter 1",
                1,
                List.of(new CurriculumAdminService.ItemSpec(
                    1,
                    CurriculumItem.ContentType.LESSON,
                    refId,
                    false,
                    "NONE"
                ))
            ))
        ));

        assertThat(result.level()).isNull();
        assertThat(result.description()).isNull();
        verify(repository).updatePath(pathId, "PATH_CODE", "Path Name", null, null, null, "PUBLIC", null);
        verify(repository).insertItem(any(), any(), eq(1), eq(CurriculumItem.ContentType.LESSON), eq(refId), eq(false), eq("PREVIOUS_REQUIRED"), eq("{}"));
    }

    @Test
    void recomputePathProgressRespectsNoneRuleOnlyForRequiredItemsAndGatesOptionalBranches() {
        UUID learnerId = UUID.randomUUID();
        UUID pathId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        UUID item1 = UUID.randomUUID();
        UUID item2 = UUID.randomUUID();
        UUID item3 = UUID.randomUUID();

        CurriculumSection sec = new CurriculumSection(sectionId, pathId, "Ch 1", 1);
        List<CurriculumItem> items = List.of(
            new CurriculumItem(item1, sectionId, 1, CurriculumItem.ContentType.VOCABULARY_REVIEW, UUID.randomUUID(), true, "PREVIOUS_REQUIRED", java.util.Map.of()),
            new CurriculumItem(item2, sectionId, 2, CurriculumItem.ContentType.LESSON, UUID.randomUUID(), false, "NONE", java.util.Map.of()),
            new CurriculumItem(item3, sectionId, 3, CurriculumItem.ContentType.GRAMMAR_PRACTICE, UUID.randomUUID(), true, "NONE", java.util.Map.of())
        );

        when(repository.findSectionsByPathId(pathId)).thenReturn(List.of(sec));
        when(repository.findItemsByPathId(pathId)).thenReturn(items);
        when(repository.findItemProgressByPath(learnerId, pathId)).thenReturn(List.of());

        CurriculumService realService = new CurriculumService(repository);
        realService.recomputePathProgress(learnerId, pathId);

        verify(repository).upsertItemProgressStatus(learnerId, item1, "UNLOCKED");
        verify(repository).upsertItemProgressStatus(learnerId, item2, "LOCKED");
        verify(repository).upsertItemProgressStatus(learnerId, item3, "UNLOCKED");
    }
}
