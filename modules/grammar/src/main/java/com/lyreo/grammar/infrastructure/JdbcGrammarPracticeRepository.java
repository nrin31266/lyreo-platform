package com.lyreo.grammar.infrastructure;

import com.lyreo.grammar.application.GrammarPracticeFilter;
import com.lyreo.grammar.application.GrammarPracticeRepository;
import com.lyreo.grammar.domain.GrammarQuestion;
import com.lyreo.grammar.domain.GrammarQuestion.ExplanationPolicy;
import com.lyreo.grammar.domain.GrammarQuestion.Option;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Explicit SQL adapter because the imported bank is read-heavy and filter-oriented. */
public final class JdbcGrammarPracticeRepository implements GrammarPracticeRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcGrammarPracticeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<GrammarQuestion> findPracticeQuestions(GrammarPracticeFilter filter, int limit) {
        GrammarPracticeFilter safe = filter == null ? new GrammarPracticeFilter(null, null, null, null) : filter;
        StringBuilder sql = new StringBuilder(BASE_SELECT).append(" WHERE 1=1 ");
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);

        if (safe.topicId() != null) {
            sql.append(" AND q.topic_id = :topicId");
            params.addValue("topicId", safe.topicId());
        }
        if (safe.subtopicId() != null) {
            sql.append(" AND q.subtopic_id = :subtopicId");
            params.addValue("subtopicId", safe.subtopicId());
        }
        if (safe.difficultyLevel() != null) {
            sql.append(" AND q.difficulty_level = :difficultyLevel");
            params.addValue("difficultyLevel", safe.difficultyLevel());
        }
        if (safe.bankSetId() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM grammar_question_membership m")
                .append(" WHERE m.question_id = q.id AND m.bank_set_id = :bankSetId)");
            params.addValue("bankSetId", safe.bankSetId());
        }

        // Imported order is deterministic when available. Randomization is a UI/session concern and
        // can be added later without making database query plans unpredictable at the foundation.
        sql.append(" ORDER BY q.difficulty_level NULLS LAST, q.source_question_number NULLS LAST, q.id LIMIT :limit");
        return jdbc.query(sql.toString(), params, (rs, rowNumber) -> mapQuestion(rs));
    }

    @Override
    public Optional<GrammarQuestion> findQuestion(UUID questionId) {
        List<GrammarQuestion> rows = jdbc.query(
            BASE_SELECT + " WHERE q.id = :questionId",
            new MapSqlParameterSource("questionId", questionId),
            (rs, rowNumber) -> mapQuestion(rs)
        );
        return rows.stream().findFirst();
    }

    @Override
    public UUID saveAttempt(
        UUID learnerId,
        UUID questionId,
        String submittedAnswer,
        boolean correct,
        Instant answeredAt
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            """
            INSERT INTO grammar_practice_attempt(
                id, learner_id, question_id, answer, correct, answered_at
            ) VALUES (
                :id, :learnerId, :questionId, :answer, :correct, :answeredAt
            )
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("learnerId", learnerId)
                .addValue("questionId", questionId)
                .addValue("answer", submittedAnswer)
                .addValue("correct", correct)
                .addValue("answeredAt", answeredAt)
        );
        return id;
    }

    private static GrammarQuestion mapQuestion(ResultSet rs) throws SQLException {
        List<Option> options = new ArrayList<>(4);
        addOption(options, "A", rs.getString("option_a"));
        addOption(options, "B", rs.getString("option_b"));
        addOption(options, "C", rs.getString("option_c"));
        addOption(options, "D", rs.getString("option_d"));

        String policy = rs.getString("explanation_policy");
        ExplanationPolicy explanationPolicy;
        try {
            explanationPolicy = ExplanationPolicy.valueOf(policy == null ? "SOURCE" : policy);
        } catch (IllegalArgumentException invalidImportedPolicy) {
            explanationPolicy = ExplanationPolicy.SOURCE;
        }

        Integer difficulty = rs.getObject("difficulty_level", Integer.class);
        return new GrammarQuestion(
            rs.getObject("id", UUID.class),
            rs.getString("question_text"),
            List.copyOf(options),
            rs.getString("correct_answer"),
            rs.getString("explanation_vi"),
            rs.getString("translation_vi"),
            rs.getString("answer_translation_vi"),
            rs.getString("vocabulary_note"),
            difficulty == null ? 0 : difficulty,
            rs.getObject("topic_id", UUID.class),
            rs.getObject("subtopic_id", UUID.class),
            explanationPolicy
        );
    }

    private static void addOption(List<Option> options, String key, String text) {
        if (text != null && !text.isBlank()) options.add(new Option(key, text));
    }

    private static final String BASE_SELECT = """
        SELECT
            q.id,
            q.question_text,
            q.option_a,
            q.option_b,
            q.option_c,
            q.option_d,
            q.correct_answer,
            q.explanation_vi,
            q.translation_vi,
            q.answer_translation_vi,
            q.vocabulary_note,
            q.difficulty_level,
            q.topic_id,
            q.subtopic_id,
            q.explanation_policy
        FROM grammar_question q
        """;
}
