package com.lyreo.curriculum.domain;

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
    String itemStatus,
    int completedRequiredItems,
    int totalRequiredItems
) {
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
            0,
            0
        );
    }
}
