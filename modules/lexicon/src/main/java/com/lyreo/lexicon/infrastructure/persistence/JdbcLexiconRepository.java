package com.lyreo.lexicon.infrastructure.persistence;

import com.lyreo.lexicon.application.port.LexiconRepository;
import com.lyreo.lexicon.domain.LexiconEntry;
import com.lyreo.lexicon.domain.LexiconEntry.*;
import com.lyreo.lexicon.domain.LexiconSearchResult;
import com.lyreo.lexicon.domain.LexiconSearchResult.MatchType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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
    private static final int MAX_SEARCH_BATCH = 128;

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
    public LexiconSearchResult search(String displayQuery, String lookupQuery, int limit) {
        List<ActiveRelease> active = jdbc.query("""
            SELECT ar.release_id, r.manifest_json->'source' AS source_metadata, r.license_text
              FROM dataset_active_release ar
              JOIN dataset_release r ON r.id = ar.release_id
             WHERE ar.domain = 'lexicon'
            """, Map.of(), (rs, row) -> new ActiveRelease(
                rs.getObject("release_id", UUID.class), rs.getString("source_metadata"),
                rs.getString("license_text")));
        if (active.isEmpty()) return LexiconSearchResult.empty();

        ActiveRelease release = active.getFirst();
        int target = limit + 1;
        Map<UUID, SearchCandidate> unique = new LinkedHashMap<>();
        for (SearchPhase phase : SearchPhase.values()) {
            int offset = 0;
            while (unique.size() < target) {
                int batch = Math.max(16, Math.min(MAX_SEARCH_BATCH, (target - unique.size()) * 2));
                MapSqlParameterSource parameters = new MapSqlParameterSource()
                    .addValue("release", release.id())
                    .addValue("display", displayQuery)
                    .addValue("query", lookupQuery)
                    .addValue("prefix", escapedPrefix(lookupQuery))
                    .addValue("batch", batch)
                    .addValue("offset", offset);
                // Forms may repeat for one entry across items. Excluding selected identities
                // makes every nonempty form batch advance without rescanning growing offsets.
                String sql = phase.sql.replace("/* exclude selected entries */", unique.isEmpty()
                    ? "" : "AND f.entry_id NOT IN (:excluded)");
                parameters.addValue("excluded", unique.keySet());
                List<SearchCandidate> page = jdbc.query(sql, parameters,
                    (rs, row) -> new SearchCandidate(rs.getObject("id", UUID.class),
                        rs.getString("display_form"), EntryType.valueOf(rs.getString("entry_type")),
                        phase.matchType, rs.getString("matched_text")));
                for (SearchCandidate candidate : page) {
                    unique.putIfAbsent(candidate.id(), candidate);
                    if (unique.size() == target) break;
                }
                if (!phase.formPhase) offset += page.size();
                if (page.size() < batch) break;
            }
            if (unique.size() == target) break;
        }

        List<SearchCandidate> selected = unique.values().stream().limit(limit).toList();
        Map<UUID, List<String>> partsOfSpeech = loadPartsOfSpeech(release.id(),
            selected.stream().map(SearchCandidate::id).toList());
        List<LexiconSearchResult.Hit> hits = selected.stream().map(candidate ->
            new LexiconSearchResult.Hit(candidate.id(), candidate.displayForm(), candidate.entryType(),
                partsOfSpeech.getOrDefault(candidate.id(), List.of()), candidate.matchType(),
                candidate.matchedText())).toList();
        return new LexiconSearchResult(hits, unique.size() > limit,
            release.sourceMetadata(), release.licenseText());
    }

    private Map<UUID, List<String>> loadPartsOfSpeech(UUID releaseId, List<UUID> entryIds) {
        if (entryIds.isEmpty()) return Map.of();
        Map<UUID, List<String>> byEntry = new HashMap<>();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
            .addValue("release", releaseId).addValue("entries", entryIds);
        jdbc.query("""
            SELECT entry_id, part_of_speech
              FROM lexicon_item
             WHERE release_id = :release AND entry_id IN (:entries)
             GROUP BY entry_id, part_of_speech
             ORDER BY entry_id, part_of_speech
            """, parameters, rs -> {
                UUID entryId = rs.getObject("entry_id", UUID.class);
                byEntry.computeIfAbsent(entryId, ignored -> new ArrayList<>())
                    .add(rs.getString("part_of_speech"));
            });
        return byEntry;
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
                   link_status, unlinked_reason, tags::text AS tags, source_pos
              FROM lexicon_translation
             WHERE release_id = :release AND entry_id IN (:entries)
             ORDER BY entry_id, id
            """, (r, n) -> new LexiconTranslation(r.getObject("id", UUID.class),
                r.getObject("item_id", UUID.class), r.getObject("sense_id", UUID.class),
                r.getString("word_vi"), r.getString("source"), r.getString("source_scope"),
                r.getString("source_sense_qualifier"), r.getString("link_status"),
                r.getString("unlinked_reason"), r.getString("tags"), r.getString("source_pos")));

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

    private record ActiveRelease(UUID id, String sourceMetadata, String licenseText) {}

    private record SearchCandidate(UUID id, String displayForm, EntryType entryType,
                                   MatchType matchType, String matchedText) {}

    /** Earlier phases win when one entry matches several paths. */
    private enum SearchPhase {
        EXACT_HEADWORD_CASE(MatchType.EXACT_HEADWORD, """
            SELECT e.id, e.display_form, e.entry_type, e.display_form AS matched_text
              FROM lexicon_entry e
             WHERE e.release_id = :release AND e.lookup_form = :query
               AND e.display_form = :display
             ORDER BY e.display_form, e.id
             LIMIT :batch OFFSET :offset
            """),
        EXACT_FORM_CASE(MatchType.EXACT_FORM, """
            SELECT e.id, e.display_form, e.entry_type, f.form AS matched_text
              FROM lexicon_form f
              JOIN lexicon_entry e ON e.id = f.entry_id AND e.release_id = f.release_id
             WHERE f.release_id = :release AND f.normalized_form = :query
               AND f.form = :display
               /* exclude selected entries */
             ORDER BY CASE WHEN jsonb_exists_any(f.tags,
                          ARRAY['archaic','obsolete','dialectal','nonstandard','misspelling','dated'])
                          OR (EXISTS (
                              SELECT 1 FROM lexicon_sense s
                               WHERE s.release_id = f.release_id AND s.item_id = f.item_id
                          ) AND NOT EXISTS (
                              SELECT 1 FROM lexicon_sense s
                               WHERE s.release_id = f.release_id AND s.item_id = f.item_id
                                 AND NOT jsonb_exists_any(s.tags,
                                     ARRAY['archaic','obsolete','dialectal','nonstandard','misspelling','dated'])
                          ))
                          THEN 1 ELSE 0 END,
                      char_length(e.display_form), e.display_form, e.id, f.id
             LIMIT :batch OFFSET :offset
            """),
        EXACT_HEADWORD_NORMALIZED(MatchType.EXACT_HEADWORD, """
            SELECT e.id, e.display_form, e.entry_type, e.display_form AS matched_text
              FROM lexicon_entry e
             WHERE e.release_id = :release AND e.lookup_form = :query
               AND e.display_form <> :display
             ORDER BY char_length(e.display_form), e.display_form, e.id
             LIMIT :batch OFFSET :offset
            """),
        EXACT_FORM_NORMALIZED(MatchType.EXACT_FORM, """
            SELECT e.id, e.display_form, e.entry_type, f.form AS matched_text
              FROM lexicon_form f
              JOIN lexicon_entry e ON e.id = f.entry_id AND e.release_id = f.release_id
             WHERE f.release_id = :release AND f.normalized_form = :query
               AND f.form <> :display
               /* exclude selected entries */
             ORDER BY CASE WHEN jsonb_exists_any(f.tags,
                          ARRAY['archaic','obsolete','dialectal','nonstandard','misspelling','dated'])
                          OR (EXISTS (
                              SELECT 1 FROM lexicon_sense s
                               WHERE s.release_id = f.release_id AND s.item_id = f.item_id
                          ) AND NOT EXISTS (
                              SELECT 1 FROM lexicon_sense s
                               WHERE s.release_id = f.release_id AND s.item_id = f.item_id
                                 AND NOT jsonb_exists_any(s.tags,
                                     ARRAY['archaic','obsolete','dialectal','nonstandard','misspelling','dated'])
                          ))
                          THEN 1 ELSE 0 END,
                      char_length(e.display_form), e.display_form, e.id, f.id
             LIMIT :batch OFFSET :offset
            """),
        PREFIX_HEADWORD(MatchType.PREFIX_HEADWORD, """
            SELECT e.id, e.display_form, e.entry_type, e.display_form AS matched_text
              FROM lexicon_entry e
             WHERE e.release_id = :release AND e.lookup_form LIKE :prefix ESCAPE '!'
               AND e.lookup_form <> :query
             ORDER BY e.lookup_form USING ~<~, e.display_form, e.id
             LIMIT :batch OFFSET :offset
            """),
        PREFIX_FORM(MatchType.PREFIX_FORM, """
            WITH page AS MATERIALIZED (
                SELECT f.id AS form_id, f.entry_id, f.normalized_form, f.form
                  FROM lexicon_form f
                 WHERE f.release_id = :release AND f.normalized_form LIKE :prefix ESCAPE '!'
                   AND f.normalized_form <> :query
                   /* exclude selected entries */
                 ORDER BY f.normalized_form USING ~<~, f.form, f.id
                 LIMIT :batch OFFSET :offset
            )
            SELECT e.id, e.display_form, e.entry_type, p.form AS matched_text
              FROM page p
              JOIN lexicon_entry e ON e.id = p.entry_id AND e.release_id = :release
             ORDER BY p.normalized_form USING ~<~, p.form, p.form_id
            """);

        private final MatchType matchType;
        private final String sql;
        private final boolean formPhase;

        SearchPhase(MatchType matchType, String sql) {
            this.matchType = matchType;
            this.sql = sql;
            this.formPhase = matchType == MatchType.EXACT_FORM || matchType == MatchType.PREFIX_FORM;
        }
    }
}
