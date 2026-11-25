package com.lyreo.vocabulary.infrastructure.persistence;

import com.lyreo.vocabulary.application.port.SpacedRepetitionScheduler;
import com.lyreo.vocabulary.application.port.VocabularyCatalog;
import com.lyreo.vocabulary.application.port.VocabularyRepository;
import com.lyreo.vocabulary.domain.VocabularyCard;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

public class JdbcVocabularyRepository implements VocabularyRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcVocabularyRepository(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, new ObjectMapper());
    }

    public JdbcVocabularyRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void lockLearnerSaveGate(UUID learnerId) {
        jdbc.queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
            Map.of("lockKey", "vocab-save:" + learnerId),
            Object.class
        );
    }

    @Override
    public VocabularyCard addIfAbsent(UUID learnerId, UUID headwordId, String contextType, UUID contextId, Instant now) {
        UUID id = UUID.randomUUID();
        String dedupKey = "LEXICON_HEADWORD:" + headwordId;
        jdbc.update("""
            INSERT INTO vocabulary_card(id, learner_id, headword_id, dedup_key, term,
                                        source_context_type, source_context_id,
                                        next_review_at, stability, difficulty, created_at)
            VALUES(:id, :learner, :headword, :dedupKey,
                   COALESCE((SELECT identity_form FROM lexicon_headword WHERE id = :headword), 'unknown'),
                   :contextType, :contextId, :now, 1, 5, :now)
            ON CONFLICT(learner_id, dedup_key) DO NOTHING
            """, new MapSqlParameterSource()
            .addValue("id", id).addValue("learner", learnerId).addValue("headword", headwordId)
            .addValue("dedupKey", dedupKey)
            .addValue("contextType", contextType).addValue("contextId", contextId).addValue("now", Timestamp.from(now)));
        return jdbc.query("""
            SELECT * FROM vocabulary_card WHERE learner_id=:learner AND dedup_key=:dedupKey
            """, Map.of("learner", learnerId, "dedupKey", dedupKey), this::map).stream().findFirst().orElseThrow();
    }

    @Override
    public Optional<VocabularyCard> findCardByDedupKey(UUID learnerId, String dedupKey) {
        return jdbc.query("""
            SELECT * FROM vocabulary_card WHERE learner_id = :learner AND dedup_key = :dedupKey
            """, Map.of("learner", learnerId, "dedupKey", dedupKey), this::map).stream().findFirst();
    }

    @Override
    public Optional<VocabularyCard> findCard(UUID learnerId, UUID cardId) {
        return jdbc.query("SELECT * FROM vocabulary_card WHERE id=:id AND learner_id=:learner",
            Map.of("id", cardId, "learner", learnerId), this::map).stream().findFirst();
    }

    @Override
    public Optional<VocabularyCard> findCardForUpdate(UUID learnerId, UUID cardId) {
        return jdbc.query("SELECT * FROM vocabulary_card WHERE id=:id AND learner_id=:learner FOR UPDATE",
            Map.of("id", cardId, "learner", learnerId), this::map).stream().findFirst();
    }

    @Override
    public int countCards(UUID learnerId) {
        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM vocabulary_card WHERE learner_id = :learner",
            Map.of("learner", learnerId),
            Integer.class
        );
        return count == null ? 0 : count;
    }

    @Override
    public VocabularyCard insertSavedCard(SaveCardCommand cmd, Instant now) {
        jdbc.update("""
            INSERT INTO vocabulary_card(
                id, learner_id, headword_id, dedup_key, term, part_of_speech,
                meaning_vi, definition_en, ipa, context_sentence, context_translation_vi,
                source_context_type, source_context_id, source_entry_id, sense_index,
                source_revision, audio_asset_id, image_asset_id, snapshot_json,
                next_review_at, stability, difficulty, created_at
            ) VALUES (
                :id, :learner, :headword, :dedupKey, :term, :pos,
                :meaningVi, :definitionEn, :ipa, :contextSentence, :contextTranslationVi,
                :contextType, :contextId, :sourceEntryId, :senseIndex,
                :sourceRevision, :audioAssetId, :imageAssetId, CAST(:snapshotJson AS jsonb),
                :now, 1, 5, :now
            )
            ON CONFLICT(learner_id, dedup_key) DO NOTHING
            """, new MapSqlParameterSource()
            .addValue("id", cmd.id())
            .addValue("learner", cmd.learnerId())
            .addValue("headword", cmd.headwordId())
            .addValue("dedupKey", cmd.dedupKey())
            .addValue("term", cmd.term())
            .addValue("pos", cmd.partOfSpeech())
            .addValue("meaningVi", cmd.meaningVi())
            .addValue("definitionEn", cmd.definitionEn())
            .addValue("ipa", cmd.ipa())
            .addValue("contextSentence", cmd.contextSentence())
            .addValue("contextTranslationVi", cmd.contextTranslationVi())
            .addValue("contextType", cmd.sourceContextType())
            .addValue("contextId", cmd.sourceContextId())
            .addValue("sourceEntryId", cmd.sourceEntryId())
            .addValue("senseIndex", cmd.senseIndex())
            .addValue("sourceRevision", cmd.sourceRevision())
            .addValue("audioAssetId", cmd.audioAssetId())
            .addValue("imageAssetId", cmd.imageAssetId())
            .addValue("snapshotJson", cmd.snapshotJson() != null ? cmd.snapshotJson() : "{}")
            .addValue("now", Timestamp.from(now)));
        return findCardByDedupKey(cmd.learnerId(), cmd.dedupKey()).orElseThrow();
    }

    @Override
    public List<VocabularyCard> due(UUID learnerId, Instant now, int limit) {
        return jdbc.query("""
            SELECT * FROM vocabulary_card
             WHERE learner_id=:learner AND suspended_at IS NULL AND next_review_at <= :now
             ORDER BY next_review_at
             LIMIT :limit
            """, new MapSqlParameterSource().addValue("learner", learnerId).addValue("now", Timestamp.from(now)).addValue("limit", limit), this::map);
    }

    @Override
    @Transactional
    public void applyReview(UUID cardId, SpacedRepetitionScheduler.Rating rating, SpacedRepetitionScheduler.ScheduleResult result, Instant reviewedAt) {
        applyReviewWithId(cardId, rating, result, reviewedAt);
    }

    @Override
    @Transactional
    public UUID applyReviewWithId(
        UUID cardId,
        SpacedRepetitionScheduler.Rating rating,
        SpacedRepetitionScheduler.ScheduleResult result,
        Instant reviewedAt
    ) {
        UUID reviewId = UUID.randomUUID();
        Timestamp previousDueAt = jdbc.queryForObject(
            "SELECT next_review_at FROM vocabulary_card WHERE id=:id", Map.of("id", cardId), Timestamp.class);
        jdbc.update("""
            INSERT INTO vocabulary_review(id,card_id,rating,previous_due_at,next_due_at,stability,difficulty,reviewed_at)
            VALUES(:id,:card,:rating,:previous,:next,:stability,:difficulty,:reviewedAt)
            """, new MapSqlParameterSource().addValue("id", reviewId).addValue("card", cardId)
            .addValue("rating", rating.name()).addValue("previous", previousDueAt).addValue("next", Timestamp.from(result.nextReviewAt()))
            .addValue("stability", result.stability()).addValue("difficulty", result.difficulty()).addValue("reviewedAt", Timestamp.from(reviewedAt)));
        jdbc.update("""
            UPDATE vocabulary_card
               SET next_review_at=:next, stability=:stability, difficulty=:difficulty,
                   review_count=review_count+1,
                   lapse_count=lapse_count + CASE WHEN :rating='AGAIN' THEN 1 ELSE 0 END
             WHERE id=:id
            """, new MapSqlParameterSource().addValue("id", cardId).addValue("next", Timestamp.from(result.nextReviewAt()))
            .addValue("stability", result.stability()).addValue("difficulty", result.difficulty()).addValue("rating", rating.name()));
        return reviewId;
    }

    @Override
    public List<UUID> findCollectionIdsForCard(UUID cardId) {
        return jdbc.queryForList(
            "SELECT collection_id FROM vocabulary_collection_item WHERE card_id = :cardId ORDER BY added_at ASC",
            Map.of("cardId", cardId),
            UUID.class
        );
    }

    @Override
    public Optional<EntrySnapshotSeed> findEntrySnapshotSeed(UUID entryId) {
        return jdbc.query("""
            SELECT id, term, content::text AS content_json, revision,
                   image_id, uk_audio_id, us_audio_id, example_audio_id, second_example_audio_id
            FROM vocabulary_unit_entry
            WHERE id = :entryId
            """, Map.of("entryId", entryId), (rs, n) -> new EntrySnapshotSeed(
            rs.getObject("id", UUID.class),
            rs.getString("term"),
            json.readValue(rs.getString("content_json"), VocabularyCatalog.LearningContent.class),
            rs.getLong("revision"),
            rs.getObject("image_id", UUID.class),
            rs.getObject("uk_audio_id", UUID.class),
            rs.getObject("us_audio_id", UUID.class),
            rs.getObject("example_audio_id", UUID.class),
            rs.getObject("second_example_audio_id", UUID.class)
        )).stream().findFirst();
    }

    @Override
    public Optional<SavedCardMediaAccess> findSavedCardMediaAccess(UUID learnerId, UUID cardId) {
        return jdbc.query("""
            SELECT c.id, c.audio_asset_id, c.image_asset_id, p.access_mode, p.required_feature_key
            FROM vocabulary_card c
            LEFT JOIN vocabulary_unit_entry e ON e.id = c.source_entry_id
            LEFT JOIN vocabulary_unit u ON u.id = e.unit_id
            LEFT JOIN vocabulary_pack p ON p.id = u.pack_id
            WHERE c.id = :cardId AND c.learner_id = :learnerId
            """, Map.of("cardId", cardId, "learnerId", learnerId), (rs, n) -> new SavedCardMediaAccess(
            rs.getObject("id", UUID.class),
            rs.getObject("audio_asset_id", UUID.class),
            rs.getObject("image_asset_id", UUID.class),
            rs.getString("access_mode"),
            rs.getString("required_feature_key")
        )).stream().findFirst();
    }

    private VocabularyCard map(ResultSet rs, int rowNum) throws SQLException {
        return mapRow(rs);
    }

    static VocabularyCard mapRow(ResultSet rs) throws SQLException {
        Integer senseIndex = rs.getObject("sense_index") == null ? null : rs.getInt("sense_index");
        Long sourceRevision = rs.getObject("source_revision") == null ? null : rs.getLong("source_revision");
        return new VocabularyCard(
            rs.getObject("id", UUID.class),
            rs.getObject("learner_id", UUID.class),
            rs.getObject("headword_id", UUID.class),
            rs.getString("dedup_key"),
            rs.getString("term"),
            rs.getString("part_of_speech"),
            rs.getString("meaning_vi"),
            rs.getString("definition_en"),
            rs.getString("ipa"),
            rs.getString("context_sentence"),
            rs.getString("context_translation_vi"),
            rs.getString("source_context_type"),
            rs.getObject("source_context_id", UUID.class),
            rs.getObject("source_entry_id", UUID.class),
            senseIndex,
            sourceRevision,
            rs.getObject("audio_asset_id", UUID.class),
            rs.getObject("image_asset_id", UUID.class),
            rs.getString("snapshot_json"),
            rs.getTimestamp("next_review_at").toInstant(),
            rs.getDouble("stability"),
            rs.getDouble("difficulty"),
            rs.getInt("lapse_count"),
            rs.getInt("review_count")
        );
    }
}
