package com.lyreo.curriculum.domain;

import java.util.List;
import java.util.UUID;

/** Server-computed health and slice diagnostics for a curriculum path. */
public record CurriculumPathDiagnostic(
    UUID pathId,
    int totalItems,
    int validItems,
    int missingItems,
    int lockedForFreeUsers,
    List<ItemCheck> checks
) {
    public record ItemCheck(
        UUID itemId,
        int sectionPosition,
        int itemPosition,
        CurriculumItem.ContentType contentType,
        UUID contentReferenceId,
        String title,
        boolean exists,
        String accessMode,
        String requiredFeatureKey,
        boolean sliceValid,
        int sliceCount,
        String message
    ) {}
}
