package com.lyreo.lexicon.infrastructure.persistence;

import com.lyreo.lexicon.application.port.LexiconRepository;
import com.lyreo.lexicon.domain.LexiconEntry;
import com.lyreo.lexicon.domain.LexiconEntry.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Reads content from the active immutable Lexicon release. */
public final class JdbcLexiconRepository implements LexiconRepository {
    private static final String BASE = """
        SELECT e.id, e.release_id, e.headword_id, e.display_form, e.lookup_form,
               e.entry_type, h.language, r.manifest_json->'source' AS source_metadata,
               r.license_text
          FROM dataset_active_release ar
          JOIN lexicon_entry e ON e.release_id = ar.release_id
          JOIN lexicon_headword h ON h.id = e.headword_id
          JOIN dataset_release r ON r.id = e.release_id
         WHERE ar.domain = 'lexicon'
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcLexiconRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<LexiconEntry> findById(UUID id) {
        List<LexiconHeader> headers = jdbc.query(BASE + " AND e.id = :id", Map.of("id", id), this::mapHeader);
        return hydrate(headers).stream().findFirst();
    }

    @Override
    public List<LexiconEntry> search(String query, int limit) {
        List<LexiconHeader> headers = jdbc.query("""
            WITH active AS (
                SELECT release_id FROM dataset_active_release WHERE domain = 'lexicon'
            ), candidates AS (
                SELECT e.id, e.release_id
                  FROM lexicon_entry e JOIN active a ON a.release_id = e.release_id
                 WHERE e.lookup_form LIKE :prefix ESCAPE '!'
                UNION
                SELECT f.entry_id AS id, f.release_id
                  FROM lexicon_form f JOIN active a ON a.release_id = f.release_id
                 WHERE f.normalized_form LIKE :prefix ESCAPE '!'
            )
            SELECT e.id, e.release_id, e.headword_id, e.display_form, e.lookup_form,
                   e.entry_type, h.language, r.manifest_json->'source' AS source_metadata,
                   r.license_text
              FROM candidates c
              JOIN lexicon_entry e ON e.id = c.id AND e.release_id = c.release_id
              JOIN lexicon_headword h ON h.id = e.headword_id
              JOIN dataset_release r ON r.id = e.release_id
            ORDER BY CASE WHEN e.lookup_form = :query THEN 0
                          WHEN e.lookup_form LIKE :prefix ESCAPE '!' THEN 1 ELSE 2 END,
                     length(e.display_form), e.display_form
            LIMIT :limit
            """, new MapSqlParameterSource().addValue("query", query)
                .addValue("prefix", escapedPrefix(query)).addValue("limit", limit), this::mapHeader);
        return hydrate(headers);
    }

    private static String escapedPrefix(String query) {
        return query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    @Override
    public Optional<LexiconEntry> findByNormalizedForm(String normalizedForm) {
        List<LexiconHeader> headers = jdbc.query(BASE + """
            AND e.lookup_form = :form
            ORDER BY CASE e.entry_type WHEN 'WORD' THEN 0 ELSE 1 END
            LIMIT 1
            """, Map.of("form", normalizedForm), this::mapHeader);
        return hydrate(headers).stream().findFirst();
    }

    private List<LexiconEntry> hydrate(List<LexiconHeader> headers) {
        if (headers.isEmpty()) return List.of();

        UUID releaseId = headers.getFirst().releaseId();
        if (headers.stream().anyMatch(header -> !header.releaseId().equals(releaseId))) {
            throw new IllegalStateException("Active Lexicon query returned entries from multiple releases");
        }
        List<UUID> entryIds = headers.stream().map(LexiconHeader::id).toList();
        Map<UUID, List<LexiconItem>> items = loadChildren(releaseId, entryIds, """
            SELECT entry_id, id, part_of_speech, pos_title, etymology_number, etymology_text, order_index
              FROM lexicon_item
             WHERE release_id = :release AND entry_id IN (:entries)
             ORDER BY entry_id, order_index, id
            """, (r, n) -> new LexiconItem(r.getObject("id", UUID.class), r.getString("part_of_speech"),
                r.getString("pos_title"), (Integer) r.getObject("etymology_number"),
                r.getString("etymology_text"), r.getInt("order_index")));
        Map<UUID, List<LexiconForm>> forms = loadChildren(releaseId, entryIds, """
            SELECT entry_id, id, item_id, form, normalized_form, tags::text AS tags
              FROM lexicon_form
             WHERE release_id = :release AND entry_id IN (:entries)
             ORDER BY entry_id, id
            """, (r, n) -> new LexiconForm(r.getObject("id", UUID.class),
                r.getObject("item_id", UUID.class), r.getString("form"), r.getString("normalized_form"), r.getString("tags")));
        Map<UUID, List<LexiconSense>> senses = loadChildren(releaseId, entryIds, """
            SELECT s.entry_id, s.id, s.item_id, s.position, i.part_of_speech, s.definition_en,
                   s.translation_vi, s.translation_status, s.raw_glosses::text AS raw_glosses,
                   s.examples::text AS examples, s.tags::text AS tags, s.matched_qualifier
              FROM lexicon_sense s
              JOIN lexicon_item i ON i.id = s.item_id
                                  AND i.release_id = s.release_id
                                  AND i.entry_id = s.entry_id
             WHERE s.release_id = :release AND s.entry_id IN (:entries)
             ORDER BY s.entry_id, i.order_index, s.position, s.id
            """, (r, n) -> new LexiconSense(r.getObject("id", UUID.class),
                r.getObject("item_id", UUID.class), r.getInt("position"), r.getString("part_of_speech"),
                r.getString("definition_en"), r.getString("translation_vi"),
                TranslationStatus.valueOf(r.getString("translation_status")), r.getString("raw_glosses"),
                r.getString("examples"), r.getString("tags"), r.getString("matched_qualifier")));
        Map<UUID, List<Pronunciation>> pronunciations = loadChildren(releaseId, entryIds, """
            SELECT entry_id, id, item_id, accent, ipa, audio_file, audio_url, source_url,
                   cached_audio_object_key, tags::text AS tags
              FROM lexicon_pronunciation
             WHERE release_id = :release AND entry_id IN (:entries)
             ORDER BY entry_id, accent NULLS LAST, id
            """, (r, n) -> new Pronunciation(r.getObject("id", UUID.class),
                r.getObject("item_id", UUID.class), r.getString("accent"), r.getString("ipa"),
                r.getString("audio_file"), r.getString("audio_url"), r.getString("source_url"),
                r.getString("cached_audio_object_key"), r.getString("tags")));
        Map<UUID, List<LexiconTranslation>> translations = loadChildren(releaseId, entryIds, """
            SELECT entry_id, id, item_id, sense_id, word_vi, source, source_scope, source_sense_qualifier,
                   link_status, unlinked_reason, tags::text AS tags
              FROM lexicon_translation
             WHERE release_id = :release AND entry_id IN (:entries)
             ORDER BY entry_id, id
            """, (r, n) -> new LexiconTranslation(r.getObject("id", UUID.class),
                r.getObject("item_id", UUID.class), r.getObject("sense_id", UUID.class),
                r.getString("word_vi"), r.getString("source"), r.getString("source_scope"),
                r.getString("source_sense_qualifier"), r.getString("link_status"),
                r.getString("unlinked_reason"), r.getString("tags")));

        return headers.stream().map(header -> new LexiconEntry(
            header.id(), header.headwordId(), header.displayForm(), header.lookupForm(), header.entryType(),
            header.language(), header.sourceMetadata(), header.licenseText(),
            getOrEmpty(items, header.id()), getOrEmpty(forms, header.id()), getOrEmpty(senses, header.id()),
            getOrEmpty(pronunciations, header.id()), getOrEmpty(translations, header.id())
        )).toList();
    }

    private LexiconHeader mapHeader(ResultSet rs, int rowNum) throws SQLException {
        return new LexiconHeader(
            rs.getObject("id", UUID.class), rs.getObject("release_id", UUID.class),
            rs.getObject("headword_id", UUID.class), rs.getString("display_form"),
            rs.getString("lookup_form"), EntryType.valueOf(rs.getString("entry_type")),
            rs.getString("language"), rs.getString("source_metadata"), rs.getString("license_text")
        );
    }

    private <T> Map<UUID, List<T>> loadChildren(
        UUID releaseId,
        Collection<UUID> entryIds,
        String sql,
        RowMapper<T> mapper
    ) {
        Map<UUID, List<T>> byEntry = new LinkedHashMap<>();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("release", releaseId)
            .addValue("entries", entryIds);
        return jdbc.query(sql, parameters, (ResultSetExtractor<Map<UUID, List<T>>>) rs -> {
            int rowNum = 0;
            while (rs.next()) {
                UUID entryId = rs.getObject("entry_id", UUID.class);
                byEntry.computeIfAbsent(entryId, ignored -> new ArrayList<>()).add(mapper.mapRow(rs, rowNum++));
            }
            return byEntry;
        });
    }

    private static <T> List<T> getOrEmpty(Map<UUID, List<T>> children, UUID entryId) {
        return children.getOrDefault(entryId, List.of());
    }

    private record LexiconHeader(
        UUID id,
        UUID releaseId,
        UUID headwordId,
        String displayForm,
        String lookupForm,
        EntryType entryType,
        String language,
        String sourceMetadata,
        String licenseText
    ) {}
}
