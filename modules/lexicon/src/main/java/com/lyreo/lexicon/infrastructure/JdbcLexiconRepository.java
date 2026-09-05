package com.lyreo.lexicon.infrastructure;

import com.lyreo.lexicon.application.LexiconRepository;
import com.lyreo.lexicon.domain.LexiconEntry;
import com.lyreo.lexicon.domain.LexiconEntry.EntryType;
import com.lyreo.lexicon.domain.LexiconEntry.LexiconSense;
import com.lyreo.lexicon.domain.LexiconEntry.Pronunciation;
import com.lyreo.lexicon.domain.LexiconEntry.TranslationStatus;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcLexiconRepository implements LexiconRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcLexiconRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<LexiconEntry> findById(UUID id) {
        return jdbc.query("""
            SELECT id, canonical_form, normalized_form, entry_type, language
              FROM lexicon_entry
             WHERE id = :id AND status = 'ACTIVE'
            """, Map.of("id", id), (rs, rowNum) -> hydrate(
                rs.getObject("id", UUID.class), rs.getString("canonical_form"),
                rs.getString("normalized_form"), rs.getString("entry_type"), rs.getString("language")
            )).stream().findFirst();
    }

    @Override
    public List<LexiconEntry> search(String query, int limit) {
        String prefix = query + "%";
        String contains = "%" + query + "%";
        return jdbc.query("""
            SELECT DISTINCT e.id, e.canonical_form, e.normalized_form, e.entry_type, e.language,
                   CASE
                     WHEN e.normalized_form = :query THEN 0
                     WHEN e.normalized_form LIKE :prefix THEN 1
                     WHEN EXISTS (SELECT 1 FROM lexicon_form f WHERE f.entry_id=e.id AND f.normalized_form LIKE :prefix) THEN 2
                     ELSE 3
                   END AS rank
              FROM lexicon_entry e
             WHERE e.status = 'ACTIVE'
               AND (
                 e.normalized_form LIKE :contains
                 OR EXISTS (SELECT 1 FROM lexicon_form f WHERE f.entry_id=e.id AND f.normalized_form LIKE :contains)
               )
             ORDER BY rank, length(e.canonical_form), e.canonical_form
             LIMIT :limit
            """, new MapSqlParameterSource()
                .addValue("query", query)
                .addValue("prefix", prefix)
                .addValue("contains", contains)
                .addValue("limit", limit),
            (rs, rowNum) -> hydrate(
                rs.getObject("id", UUID.class), rs.getString("canonical_form"),
                rs.getString("normalized_form"), rs.getString("entry_type"), rs.getString("language")
            ));
    }

    @Override
    public Optional<LexiconEntry> findByNormalizedForm(String normalizedForm) {
        return jdbc.query("""
            SELECT id, canonical_form, normalized_form, entry_type, language
              FROM lexicon_entry
             WHERE normalized_form = :form AND status = 'ACTIVE'
             ORDER BY CASE entry_type WHEN 'WORD' THEN 0 ELSE 1 END
             LIMIT 1
            """, Map.of("form", normalizedForm), (rs, rowNum) -> hydrate(
                rs.getObject("id", UUID.class), rs.getString("canonical_form"),
                rs.getString("normalized_form"), rs.getString("entry_type"), rs.getString("language")
            )).stream().findFirst();
    }

    private LexiconEntry hydrate(UUID id, String canonical, String normalized, String type, String language) {
        List<LexiconSense> senses = jdbc.query("""
            SELECT id, part_of_speech, definition_en, translation_vi, translation_status, source_id
              FROM lexicon_sense
             WHERE entry_id = :id
             ORDER BY position
            """, Map.of("id", id), (rs, rowNum) -> new LexiconSense(
                rs.getObject("id", UUID.class), rs.getString("part_of_speech"),
                rs.getString("definition_en"), rs.getString("translation_vi"),
                TranslationStatus.valueOf(rs.getString("translation_status")),
                rs.getObject("source_id", UUID.class)
            ));
        List<Pronunciation> pronunciations = jdbc.query("""
            SELECT accent, ipa, external_audio_url, cached_audio_object_key, source_id
              FROM lexicon_pronunciation
             WHERE entry_id = :id
             ORDER BY accent NULLS LAST
            """, Map.of("id", id), (rs, rowNum) -> new Pronunciation(
                rs.getString("accent"), rs.getString("ipa"), rs.getString("external_audio_url"),
                rs.getString("cached_audio_object_key"), rs.getObject("source_id", UUID.class)
            ));
        return new LexiconEntry(id, canonical, normalized, EntryType.valueOf(type), language, senses, pronunciations);
    }
}
