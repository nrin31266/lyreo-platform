package com.lyreo.vocabulary.infrastructure.persistence;

import com.lyreo.vocabulary.application.port.SpacedRepetitionScheduler;
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

public class JdbcVocabularyRepository implements VocabularyRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcVocabularyRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public VocabularyCard addIfAbsent(UUID learnerId, UUID headwordId, String contextType, UUID contextId, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO vocabulary_card(id, learner_id, headword_id, source_context_type, source_context_id,
                                        next_review_at, stability, difficulty, created_at)
            VALUES(:id,:learner,:headword,:contextType,:contextId,:now,1,5,:now)
            ON CONFLICT(learner_id, headword_id) DO NOTHING
            """, new MapSqlParameterSource()
            .addValue("id", id).addValue("learner", learnerId).addValue("headword", headwordId)
            .addValue("contextType", contextType).addValue("contextId", contextId).addValue("now", Timestamp.from(now)));
        return jdbc.query("""
            SELECT * FROM vocabulary_card WHERE learner_id=:learner AND headword_id=:headword
            """, Map.of("learner", learnerId, "headword", headwordId), this::map).stream().findFirst().orElseThrow();
    }

    @Override
    public Optional<VocabularyCard> findCard(UUID learnerId, UUID cardId) {
        return jdbc.query("SELECT * FROM vocabulary_card WHERE id=:id AND learner_id=:learner",
            Map.of("id", cardId, "learner", learnerId), this::map).stream().findFirst();
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
        Timestamp previousDueAt = jdbc.queryForObject(
            "SELECT next_review_at FROM vocabulary_card WHERE id=:id", Map.of("id", cardId), Timestamp.class);
        jdbc.update("""
            INSERT INTO vocabulary_review(id,card_id,rating,previous_due_at,next_due_at,stability,difficulty,reviewed_at)
            VALUES(:id,:card,:rating,:previous,:next,:stability,:difficulty,:reviewedAt)
            """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("card", cardId)
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
    }

    private VocabularyCard map(ResultSet rs, int rowNum) throws SQLException {
        return new VocabularyCard(
            rs.getObject("id", UUID.class), rs.getObject("learner_id", UUID.class), rs.getObject("headword_id", UUID.class),
            rs.getString("source_context_type"), rs.getObject("source_context_id", UUID.class),
            rs.getTimestamp("next_review_at").toInstant(), rs.getDouble("stability"), rs.getDouble("difficulty"),
            rs.getInt("lapse_count"), rs.getInt("review_count")
        );
    }
}
