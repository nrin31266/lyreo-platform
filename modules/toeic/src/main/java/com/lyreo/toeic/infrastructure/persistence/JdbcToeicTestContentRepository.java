package com.lyreo.toeic.infrastructure.persistence;

import com.lyreo.toeic.application.port.ToeicTestContentRepository;
import com.lyreo.toeic.domain.ToeicTestContent;
import com.lyreo.toeic.domain.ToeicTestContent.Document;
import com.lyreo.toeic.domain.ToeicTestContent.Item;
import com.lyreo.toeic.domain.ToeicTestContent.MediaAsset;
import com.lyreo.toeic.domain.ToeicTestContent.Option;
import com.lyreo.toeic.domain.ToeicTestContent.Placement;
import com.lyreo.toeic.domain.ToeicTestContent.StimulusGroup;
import com.lyreo.toeic.domain.ToeicTestContent.TestMetadata;
import java.lang.reflect.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL read adapter for an active TOEIC version and its release-scoped content. */
public final class JdbcToeicTestContentRepository implements ToeicTestContentRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcToeicTestContentRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ToeicTestContent> findActiveTestContent(UUID catalogId, UUID testVersionId) {
        Optional<ActiveTestData> activeTest = findActiveMetadata(catalogId, testVersionId);
        if (activeTest.isEmpty()) return Optional.empty();
        UUID releaseId = activeTest.get().releaseId();
        Map<UUID, GroupBuilder> groups = findGroups(testVersionId, releaseId);
        Map<UUID, List<MediaAsset>> groupMedia = new LinkedHashMap<>();
        Map<UUID, List<MediaAsset>> itemMedia = new LinkedHashMap<>();
        findMedia(testVersionId, releaseId).forEach(media -> {
            if (media.groupId() != null) {
                groupMedia.computeIfAbsent(media.groupId(), ignored -> new ArrayList<>()).add(media.asset());
            } else if (media.itemId() != null) {
                itemMedia.computeIfAbsent(media.itemId(), ignored -> new ArrayList<>()).add(media.asset());
            }
        });

        List<StimulusGroup> stimulusGroups = groups.values().stream()
            .map(group -> group.build(groupMedia.getOrDefault(group.id, List.of())))
            .toList();
        List<Placement> placements = findPlacements(testVersionId, releaseId).stream()
            .map(row -> new Placement(
                row.placementId(),
                row.groupId(),
                row.section(),
                row.part(),
                row.questionNumber(),
                row.gapNumber(),
                row.orderIndex(),
                new Item(
                    row.itemId(),
                    row.kind(),
                    row.part() <= 2 ? null : row.stemEn(),
                    row.part() <= 2
                        ? row.options().stream().map(option -> new Option(option.key(), "")).toList()
                        : row.options(),
                    row.difficultyLevel(),
                    itemMedia.getOrDefault(row.itemId(), List.of())
                )
            ))
            .toList();

        return Optional.of(new ToeicTestContent(
            catalogId,
            testVersionId,
            activeTest.get().metadata(),
            stimulusGroups,
            placements
        ));
    }

    private Optional<ActiveTestData> findActiveMetadata(UUID catalogId, UUID testVersionId) {
        return jdbc.query(
            """
            SELECT tv.release_id, tv.name, tv.year, tv.test_number, tv.set_id, tv.order_index, tv.source_label,
                   tv.listening_duration_seconds, tv.reading_duration_seconds,
                   tv.total_questions, tv.difficulty_level, tv.media_version
              FROM dataset_active_release ar
              JOIN toeic_test_version tv ON tv.release_id = ar.release_id
              JOIN toeic_test_catalog tc ON tc.id = tv.catalog_id
             WHERE ar.domain = 'grammar-toeic'
               AND tc.id = :catalogId
               AND tv.id = :testVersionId
            """,
            parameters(catalogId, testVersionId),
            (rs, rowNum) -> new ActiveTestData(
                rs.getObject("release_id", UUID.class),
                new TestMetadata(
                    rs.getString("name"),
                    rs.getInt("year"),
                    rs.getInt("test_number"),
                    rs.getObject("set_id", UUID.class),
                    rs.getObject("order_index", Integer.class),
                    rs.getString("source_label"),
                    rs.getObject("listening_duration_seconds", Integer.class),
                    rs.getObject("reading_duration_seconds", Integer.class),
                    rs.getInt("total_questions"),
                    rs.getObject("difficulty_level", Integer.class),
                    rs.getObject("media_version", Integer.class)
                )
            )
        ).stream().findFirst();
    }

    private Map<UUID, GroupBuilder> findGroups(UUID testVersionId, UUID releaseId) {
        Map<UUID, GroupBuilder> groups = new LinkedHashMap<>();
        jdbc.query(
            """
            SELECT g.id AS group_id, g.part, g.kind, g.title, g.order_index, g.difficulty_level,
                   g.question_numbers, g.render_html, g.document_parse_status, g.document_count,
                   d.id AS document_id, d.ordinal, d.document_type, d.html, d.parse_status
              FROM toeic_stimulus_group g
              JOIN toeic_test_version tv
                ON tv.id = g.test_version_id AND tv.release_id = g.release_id
              LEFT JOIN toeic_document d
                ON d.group_id = g.id AND d.release_id = g.release_id
             WHERE g.test_version_id = :testVersionId
               AND g.release_id = :releaseId
             ORDER BY g.order_index NULLS LAST, g.id, d.ordinal
            """,
            new MapSqlParameterSource()
                .addValue("testVersionId", testVersionId)
                .addValue("releaseId", releaseId),
            rs -> {
                while (rs.next()) {
                    UUID groupId = rs.getObject("group_id", UUID.class);
                    GroupBuilder group = groups.computeIfAbsent(groupId, ignored -> mapGroup(rs));
                    UUID documentId = rs.getObject("document_id", UUID.class);
                    if (documentId != null) {
                        group.documents.add(new Document(
                            documentId,
                            rs.getInt("ordinal"),
                            rs.getString("document_type"),
                            rs.getString("html"),
                            rs.getString("parse_status")
                        ));
                    }
                }
                return groups;
            }
        );
        return groups;
    }

    private static GroupBuilder mapGroup(ResultSet rs) {
        try {
            return new GroupBuilder(
                rs.getObject("group_id", UUID.class),
                rs.getInt("part"),
                rs.getString("kind"),
                rs.getString("title"),
                rs.getObject("order_index", Integer.class),
                rs.getObject("difficulty_level", Integer.class),
                readIntegerArray(rs, "question_numbers"),
                rs.getString("render_html"),
                rs.getString("document_parse_status"),
                rs.getInt("document_count")
            );
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read TOEIC stimulus group", failure);
        }
    }

    private List<PlacementRow> findPlacements(UUID testVersionId, UUID releaseId) {
        return jdbc.query(
            """
            SELECT p.id AS placement_id, p.group_id, p.section, p.part, p.question_number,
                   p.gap_number, p.order_index, ai.id AS item_id, ai.kind, ai.stem_en,
                   ai.options::text AS options_json,
                   ai.difficulty_level AS item_difficulty
              FROM toeic_placement p
              JOIN toeic_test_version tv
                ON tv.id = p.test_version_id AND tv.release_id = p.release_id
              JOIN assessment_item ai
                ON ai.id = p.item_id AND ai.release_id = p.release_id
             WHERE p.test_version_id = :testVersionId
               AND p.release_id = :releaseId
             ORDER BY p.order_index, p.question_number, p.id
            """,
            new MapSqlParameterSource()
                .addValue("testVersionId", testVersionId)
                .addValue("releaseId", releaseId),
            (rs, rowNum) -> new PlacementRow(
                rs.getObject("placement_id", UUID.class),
                rs.getObject("group_id", UUID.class),
                rs.getString("section"),
                rs.getInt("part"),
                rs.getInt("question_number"),
                rs.getObject("gap_number", Integer.class),
                rs.getInt("order_index"),
                rs.getObject("item_id", UUID.class),
                rs.getString("kind"),
                rs.getString("stem_en"),
                readOptions(rs.getString("options_json")),
                rs.getObject("item_difficulty", Integer.class)
            )
        );
    }

    private List<ScopedMedia> findMedia(UUID testVersionId, UUID releaseId) {
        return jdbc.query(
            """
            SELECT DISTINCT mau.group_id, mau.item_id, rma.package_asset_id, mau.role,
                   mau.media_version, mb.storage_object_key, mb.mime_type, mb.size_bytes
              FROM toeic_test_version tv
              JOIN media_asset_use mau ON mau.release_id = tv.release_id
              JOIN release_media_asset rma
                ON rma.release_id = mau.release_id AND rma.package_asset_id = mau.asset_id
              JOIN media_blob mb ON mb.sha256 = rma.blob_sha256
             WHERE tv.id = :testVersionId
               AND tv.release_id = :releaseId
               AND (
                   mau.group_id IN (
                       SELECT g.id FROM toeic_stimulus_group g
                        WHERE g.test_version_id = tv.id AND g.release_id = tv.release_id
                   )
                   OR mau.item_id IN (
                       SELECT p.item_id FROM toeic_placement p
                        WHERE p.test_version_id = tv.id AND p.release_id = tv.release_id
                   )
               )
             ORDER BY mau.group_id NULLS LAST, mau.item_id NULLS LAST, mau.role, rma.package_asset_id
            """,
            new MapSqlParameterSource()
                .addValue("testVersionId", testVersionId)
                .addValue("releaseId", releaseId),
            (rs, rowNum) -> new ScopedMedia(
                rs.getObject("group_id", UUID.class),
                rs.getObject("item_id", UUID.class),
                new MediaAsset(
                    rs.getString("package_asset_id"),
                    rs.getString("role"),
                    rs.getObject("media_version", Integer.class),
                    rs.getString("storage_object_key"),
                    rs.getString("mime_type"),
                    rs.getLong("size_bytes")
                )
            )
        );
    }

    private List<Option> readOptions(String value) {
        try {
            return List.copyOf(objectMapper.readValue(value, new TypeReference<List<Option>>() {}));
        } catch (JacksonException invalidOptions) {
            throw new IllegalStateException("Stored TOEIC assessment item options are invalid JSON", invalidOptions);
        }
    }

    private static List<Integer> readIntegerArray(ResultSet rs, String column) {
        try {
            java.sql.Array sqlArray = rs.getArray(column);
            Object values = sqlArray.getArray();
            int length = Array.getLength(values);
            List<Integer> result = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                result.add(((Number) Array.get(values, index)).intValue());
            }
            sqlArray.free();
            return List.copyOf(result);
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not read TOEIC stimulus question numbers", failure);
        }
    }

    private static MapSqlParameterSource parameters(UUID catalogId, UUID testVersionId) {
        return new MapSqlParameterSource()
            .addValue("catalogId", catalogId)
            .addValue("testVersionId", testVersionId);
    }

    private static final class GroupBuilder {
        private final UUID id;
        private final int part;
        private final String kind;
        private final String title;
        private final Integer orderIndex;
        private final Integer difficultyLevel;
        private final List<Integer> questionNumbers;
        private final String renderHtml;
        private final String documentParseStatus;
        private final int documentCount;
        private final List<Document> documents = new ArrayList<>();

        private GroupBuilder(
            UUID id,
            int part,
            String kind,
            String title,
            Integer orderIndex,
            Integer difficultyLevel,
            List<Integer> questionNumbers,
            String renderHtml,
            String documentParseStatus,
            int documentCount
        ) {
            this.id = id;
            this.part = part;
            this.kind = kind;
            this.title = title;
            this.orderIndex = orderIndex;
            this.difficultyLevel = difficultyLevel;
            this.questionNumbers = questionNumbers;
            this.renderHtml = renderHtml;
            this.documentParseStatus = documentParseStatus;
            this.documentCount = documentCount;
        }

        private StimulusGroup build(List<MediaAsset> media) {
            return new StimulusGroup(
                id, part, kind, title, orderIndex, difficultyLevel, questionNumbers,
                renderHtml,
                documentParseStatus, documentCount, List.copyOf(documents), List.copyOf(media)
            );
        }
    }

    private record PlacementRow(
        UUID placementId,
        UUID groupId,
        String section,
        int part,
        int questionNumber,
        Integer gapNumber,
        int orderIndex,
        UUID itemId,
        String kind,
        String stemEn,
        List<Option> options,
        Integer difficultyLevel
    ) {}

    private record ScopedMedia(UUID groupId, UUID itemId, MediaAsset asset) {}

    private record ActiveTestData(UUID releaseId, TestMetadata metadata) {}
}
