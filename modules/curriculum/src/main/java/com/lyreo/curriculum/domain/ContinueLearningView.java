package com.lyreo.curriculum.domain;

import java.util.Map;
import java.util.UUID;

public record ContinueLearningView(
    boolean hasNext,
    UUID pathId,
    String pathCode,
    String pathName,
    UUID sectionId,
    String sectionTitle,
    UUID itemId,
    CurriculumItem.ContentType contentType,
    UUID contentReferenceId,
    String contentTitle,
    Map<String, Object> config,
    UUID coverAssetId,
    String coverUrl,
    String itemStatus,
    int completedRequiredItems,
    int totalRequiredItems
) {
    public ContinueLearningView(
        boolean hasNext,
        UUID pathId,
        String pathCode,
        String pathName,
        UUID sectionId,
        String sectionTitle,
        UUID itemId,
        CurriculumItem.ContentType contentType,
        UUID contentReferenceId,
        String itemStatus,
        int completedRequiredItems,
        int totalRequiredItems
    ) {
        this(
            hasNext, pathId, pathCode, pathName, sectionId, sectionTitle,
            itemId, contentType, contentReferenceId, null, Map.of(), null, null,
            itemStatus, completedRequiredItems, totalRequiredItems
        );
    }

    public static ContinueLearningView empty() {
        return new ContinueLearningView(
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            null,
            null,
            null,
            0,
            0
        );
    }
}
