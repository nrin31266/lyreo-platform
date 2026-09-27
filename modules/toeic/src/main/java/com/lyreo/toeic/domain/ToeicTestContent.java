package com.lyreo.toeic.domain;

import java.util.List;
import java.util.UUID;

/** Published, learner-safe content for one immutable TOEIC test version. */
public record ToeicTestContent(
    UUID catalogId,
    UUID testVersionId,
    TestMetadata test,
    List<StimulusGroup> groups,
    List<Placement> placements
) {
    public record TestMetadata(
        String name,
        int year,
        int testNumber,
        UUID setId,
        Integer orderIndex,
        String sourceLabel,
        Integer listeningDurationSeconds,
        Integer readingDurationSeconds,
        int totalQuestions,
        Integer difficultyLevel,
        Integer mediaVersion
    ) {}

    public record StimulusGroup(
        UUID id,
        int part,
        String kind,
        String title,
        Integer orderIndex,
        Integer difficultyLevel,
        List<Integer> questionNumbers,
        String renderHtml,
        String documentParseStatus,
        int documentCount,
        List<Document> documents,
        List<MediaAsset> media
    ) {}

    public record Document(
        UUID id,
        int ordinal,
        String documentType,
        String html,
        String parseStatus
    ) {}

    public record Placement(
        UUID id,
        UUID groupId,
        String section,
        int part,
        int questionNumber,
        Integer gapNumber,
        int orderIndex,
        Item item
    ) {}

    public record Item(
        UUID id,
        String kind,
        String stemEn,
        List<Option> options,
        Integer difficultyLevel,
        List<MediaAsset> media
    ) {}

    public record Option(String key, String text) {}

    /** Object-storage metadata only; source provenance URLs are intentionally not runtime fields. */
    public record MediaAsset(
        String packageAssetId,
        String role,
        Integer mediaVersion,
        String storageObjectKey,
        String mimeType,
        long sizeBytes
    ) {}
}
