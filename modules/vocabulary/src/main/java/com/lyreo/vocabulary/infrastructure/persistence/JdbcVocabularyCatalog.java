package com.lyreo.vocabulary.infrastructure.persistence;

import com.lyreo.contracts.errors.RequestValidationException;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import com.lyreo.contracts.errors.StateConflictException;
import com.lyreo.vocabulary.application.port.VocabularyCatalog;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcVocabularyCatalog implements VocabularyCatalog {
    private static final String ENTRY_FILTER = """
         FROM vocabulary_unit_entry e
         JOIN vocabulary_unit u ON u.id = e.unit_id
         JOIN vocabulary_pack p ON p.id = u.pack_id
         CROSS JOIN LATERAL (
             SELECT (e.hidden OR u.hidden OR p.hidden) AS hidden,
                    COALESCE((e.content->>'needsReview')::boolean, false) AS needs_review,
                    EXISTS (SELECT 1 FROM jsonb_array_elements(e.content->'senses') AS sense
                            WHERE nullif(btrim(sense->>'meaningVi'), '') IS NULL) AS missing_vi
         ) quality
         WHERE (CAST(:pack AS uuid) IS NULL OR p.id = :pack)
           AND (CAST(:unit AS uuid) IS NULL OR u.id = :unit)
           AND (CASE :review
               WHEN 'NEEDS_REVIEW' THEN quality.needs_review
               WHEN 'MISSING_VI' THEN quality.missing_vi
               WHEN 'MISSING_SYNONYMS' THEN e.content->'synonyms' = '[]'::jsonb
               WHEN 'MISSING_ANTONYMS' THEN e.content->'antonyms' = '[]'::jsonb
               WHEN 'HIDDEN' THEN quality.hidden
               ELSE true END)
           AND (e.term ILIKE :pattern ESCAPE '!'
                OR EXISTS (SELECT 1 FROM jsonb_array_elements(e.content->'senses') AS sense
                           WHERE sense->>'definitionEn' ILIKE :pattern ESCAPE '!'
                              OR sense->>'meaningVi' ILIKE :pattern ESCAPE '!')
                OR EXISTS (SELECT 1 FROM jsonb_array_elements(COALESCE(NULLIF(e.content->'contextNotes', 'null'), '[]')) AS note
                           WHERE note->>'meaningVi' ILIKE :pattern ESCAPE '!'))
        """;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcVocabularyCatalog(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<Pack> packs() {
        return jdbc.query("""
            SELECT p.id, p.title_en, p.title_vi, p.hidden, p.revision, count(DISTINCT u.id), count(e.id),
                   (SELECT e2.image_id FROM vocabulary_unit_entry e2
                    JOIN vocabulary_unit u2 ON u2.id = e2.unit_id
                    WHERE u2.pack_id = p.id AND e2.image_id IS NOT NULL
                    ORDER BY u2.position, e2.position LIMIT 1)
            FROM vocabulary_pack p
            LEFT JOIN vocabulary_unit u ON u.pack_id = p.id
            LEFT JOIN vocabulary_unit_entry e ON e.unit_id = u.id
            GROUP BY p.id ORDER BY p.position
            """, (rs, n) -> new Pack(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
            rs.getBoolean(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getObject(8, UUID.class)));
    }

    @Override
    public List<Unit> units(UUID packId) {
        return jdbc.query("""
            SELECT u.id, u.title, u.level, u.hidden, u.revision, count(e.id)
            FROM vocabulary_unit u
            LEFT JOIN vocabulary_unit_entry e ON e.unit_id = u.id
            WHERE u.pack_id = :pack
            GROUP BY u.id ORDER BY u.position
            """, Map.of("pack", packId), (rs, n) -> new Unit(rs.getObject(1, UUID.class), rs.getString(2),
            rs.getString(3), rs.getBoolean(4), rs.getLong(5), rs.getLong(6)));
    }

    @Override
    @Transactional(readOnly = true)
    public EntryPage entries(UUID packId, UUID unitId, String query, Review review, int page) {
        var pattern = "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var params = new MapSqlParameterSource().addValue("pack", packId).addValue("unit", unitId)
            .addValue("query", query).addValue("review", review.name()).addValue("pattern", pattern).addValue("limit", PAGE_SIZE).addValue("offset", page * PAGE_SIZE);
        long total = jdbc.queryForObject("SELECT count(*)" + ENTRY_FILTER, params, Long.class);
        var items = jdbc.query("""
            SELECT e.id, p.id, u.id, u.title, u.level, e.term,
                   COALESCE(e.content #>> '{contextNotes,0,pos}', e.content #>> '{senses,0,pos}'),
                   COALESCE(e.content->>'ipaUs', e.content->>'ipaUk'),
                   jsonb_array_length(e.content->'senses'),
                   e.content #>> '{senses,0,definitionEn}',
                   COALESCE(e.content #>> '{contextNotes,0,meaningVi}', e.content #>> '{senses,0,meaningVi}'),
                   COALESCE(e.us_audio_id, e.uk_audio_id),
                   quality.hidden, quality.needs_review, quality.missing_vi
            """ + ENTRY_FILTER + """
            ORDER BY lower(e.term) = lower(:query) DESC, p.position, u.position, e.position
            LIMIT :limit OFFSET :offset
            """, params, (rs, n) -> new EntrySummary(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getInt(9), rs.getString(10), rs.getString(11), rs.getObject(12, UUID.class),
                rs.getBoolean(13), rs.getBoolean(14), rs.getBoolean(15)));
        return new EntryPage(items, total);
    }

    @Override
    public Entry entry(UUID id) {
        return jdbc.query("""
            SELECT term, hidden, revision, content, image_id, uk_audio_id, us_audio_id,
                   example_audio_id, second_example_audio_id
            FROM vocabulary_unit_entry WHERE id = :id
            """, Map.of("id", id), (rs, n) -> new Entry(id, rs.getString(1), rs.getBoolean(2),
            rs.getLong(3), json.readValue(rs.getString(4), LearningContent.class),
            new Media(rs.getObject(5, UUID.class), rs.getObject(6, UUID.class), rs.getObject(7, UUID.class),
                rs.getObject(8, UUID.class), rs.getObject(9, UUID.class))))
            .stream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Vocabulary entry not found"));
    }

    @Override
    public void savePack(UUID id, PackEdit edit) {
        requireUpdate(jdbc.update("""
            UPDATE vocabulary_pack
            SET title_en = :en, title_vi = :vi, hidden = :hidden, revision = revision + 1
            WHERE id = :id AND revision = :revision
            """, Map.of("id", id, "en", edit.titleEn().strip(), "vi", edit.titleVi().strip(),
            "hidden", edit.hidden(), "revision", edit.revision())));
    }

    @Override
    public void saveUnit(UUID id, UnitEdit edit) {
        requireUpdate(jdbc.update("""
            UPDATE vocabulary_unit
            SET title = :title, hidden = :hidden, level = COALESCE(:level, level), revision = revision + 1
            WHERE id = :id AND revision = :revision
            """, new MapSqlParameterSource().addValue("id", id).addValue("title", edit.title().strip())
            .addValue("hidden", edit.hidden()).addValue("level", edit.level()).addValue("revision", edit.revision())));
    }

    @Override
    public void saveEntry(UUID id, EntryEdit edit) {
        var content = edit.content();
        if (content.collocationsVi() != null && content.collocationsVi().size() != content.collocations().size()) {
            throw new RequestValidationException("Collocation translations must match the collocation list");
        }
        requireUpdate(jdbc.update("""
            UPDATE vocabulary_unit_entry
            SET example_audio_id = CASE WHEN content #>> '{examples,0}' = :firstExample THEN example_audio_id END,
                second_example_audio_id = CASE WHEN content #>> '{examples,1}' = :secondExample THEN second_example_audio_id END,
                content = (CAST(:content AS jsonb) - 'contextNotes' - 'translationDraft') ||
                    jsonb_build_object('contextNotes', COALESCE(content->'contextNotes', '[]'::jsonb)),
                hidden = :hidden, revision = revision + 1
            WHERE id = :id AND revision = :revision
            """, Map.of("id", id, "content", json.writeValueAsString(edit.content()),
            "hidden", edit.hidden(), "revision", edit.revision(), "firstExample", edit.content().examples().get(0),
            "secondExample", edit.content().examples().get(1))));
    }

    private static void requireUpdate(int rows) {
        if (rows != 1) {
            throw new StateConflictException("Vocabulary content changed or is unavailable. Reload and try again.");
        }
    }
}
