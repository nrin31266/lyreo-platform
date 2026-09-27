package com.lyreo.grammar.infrastructure.persistence;

import com.lyreo.grammar.application.GrammarPracticeFilter;
import com.lyreo.grammar.application.port.GrammarPracticeRepository;
import com.lyreo.grammar.domain.GrammarQuestion;
import com.lyreo.grammar.domain.GrammarQuestion.ExplanationPolicy;
import com.lyreo.grammar.domain.GrammarQuestion.Option;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** PostgreSQL adapter over the active immutable Grammar release and shared assessment items. */
public final class JdbcGrammarPracticeRepository implements GrammarPracticeRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcGrammarPracticeRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<String> findRequiredFeatureKeys() {
        return jdbc.queryForList(
            """
            WITH active_release AS (
                SELECT release_id FROM dataset_active_release WHERE domain = 'grammar-toeic'
            ), routes AS (
                %s
            )
            SELECT DISTINCT feature_key
              FROM (
                SELECT primary_feature_key AS feature_key
                  FROM routes
                 WHERE primary_publication_status = 'PUBLISHED'
                   AND primary_access_mode = 'FEATURE'
                UNION ALL
                SELECT secondary_feature_key AS feature_key
                  FROM routes
                 WHERE secondary_catalog_id IS NOT NULL
                   AND secondary_publication_status = 'PUBLISHED'
                   AND secondary_access_mode = 'FEATURE'
              ) keys
             WHERE feature_key IS NOT NULL
            """.formatted(routeUnion("", "")),
            java.util.Map.of(),
            String.class
        );
    }

    @Override
    public List<CatalogAccess> findCatalogAccessPolicies(GrammarPracticeFilter filter) {
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        List<String> queries = new ArrayList<>();

        if (filter.topicCatalogId() != null) {
            parameters.addValue("topicCatalogId", filter.topicCatalogId());
            queries.add("""
                SELECT tc.id AS catalog_id, tc.publication_status, tc.access_mode, tc.required_feature_key
                  FROM grammar_topic_catalog tc
                  JOIN grammar_topic_version tv ON tv.catalog_id = tc.id
                  JOIN dataset_active_release ar ON ar.release_id = tv.release_id AND ar.domain = 'grammar-toeic'
                 WHERE tc.id = :topicCatalogId
                """);
        }
        if (filter.subtopicCatalogId() != null) {
            parameters.addValue("subtopicCatalogId", filter.subtopicCatalogId());
            queries.add("""
                SELECT tc.id AS catalog_id, tc.publication_status, tc.access_mode, tc.required_feature_key
                  FROM grammar_subtopic_catalog sc
                  JOIN grammar_subtopic_version sv ON sv.catalog_id = sc.id
                  JOIN dataset_active_release ar ON ar.release_id = sv.release_id AND ar.domain = 'grammar-toeic'
                  JOIN grammar_topic_catalog tc ON tc.id = sc.topic_catalog_id
                  JOIN grammar_topic_version tv ON tv.catalog_id = tc.id AND tv.release_id = sv.release_id
                 WHERE sc.id = :subtopicCatalogId
                UNION ALL
                SELECT sc.id AS catalog_id, sc.publication_status, sc.access_mode, sc.required_feature_key
                  FROM grammar_subtopic_catalog sc
                  JOIN grammar_subtopic_version sv ON sv.catalog_id = sc.id
                  JOIN dataset_active_release ar ON ar.release_id = sv.release_id AND ar.domain = 'grammar-toeic'
                 WHERE sc.id = :subtopicCatalogId
                """);
        }
        if (filter.bankCatalogId() != null) {
            parameters.addValue("bankCatalogId", filter.bankCatalogId());
            queries.add("""
                SELECT bc.id AS catalog_id, bc.publication_status, bc.access_mode, bc.required_feature_key
                  FROM grammar_bank_catalog bc
                  JOIN grammar_bank_version bv ON bv.catalog_id = bc.id
                  JOIN dataset_active_release ar ON ar.release_id = bv.release_id AND ar.domain = 'grammar-toeic'
                 WHERE bc.id = :bankCatalogId
                """);
        }
        if (queries.isEmpty()) return List.of();

        String sql = String.join(" UNION ALL ", queries);
        return jdbc.query(sql, parameters, (rs, rowNum) -> new CatalogAccess(
            rs.getObject("catalog_id", UUID.class),
            rs.getString("publication_status"),
            rs.getString("access_mode"),
            rs.getString("required_feature_key")
        ));
    }

    @Override
    public List<AccessRequirement> findAccessRequirements(UUID itemId) {
        return jdbc.query(
            """
            WITH active_release AS (
                SELECT release_id FROM dataset_active_release WHERE domain = 'grammar-toeic'
            ), routes AS (
                %s
            )
            SELECT primary_publication_status, primary_access_mode, primary_feature_key,
                   secondary_catalog_id, secondary_publication_status, secondary_access_mode,
                   secondary_feature_key
              FROM routes
             WHERE item_id = :itemId
            """.formatted(routeUnion("", "")),
            new MapSqlParameterSource("itemId", itemId),
            (rs, rowNum) -> new AccessRequirement(
                isPublishedPath(rs),
                featureKeys(rs)
            )
        );
    }

    @Override
    public List<GrammarQuestion> findPracticeQuestions(
        GrammarPracticeFilter filter,
        Set<String> allowedFeatureKeys,
        int limit
    ) {
        SqlFragment routes = filteredRoutes(filter);
        MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("limit", limit);
        if (filter.topicCatalogId() != null) parameters.addValue("topicCatalogId", filter.topicCatalogId());
        if (filter.subtopicCatalogId() != null) parameters.addValue("subtopicCatalogId", filter.subtopicCatalogId());
        if (filter.bankCatalogId() != null) parameters.addValue("bankCatalogId", filter.bankCatalogId());
        if (filter.difficultyLevel() != null) parameters.addValue("difficultyLevel", filter.difficultyLevel());
        String featurePredicate = featurePredicate(allowedFeatureKeys, parameters);
        String difficultyPredicate = filter.difficultyLevel() == null
            ? ""
            : """
               AND EXISTS (
                   SELECT 1 FROM grammar_membership dm
                    WHERE dm.release_id = q.release_id AND dm.item_id = q.id
                      AND dm.mode = 'difficulty' AND dm.difficulty_level = :difficultyLevel
               )
               """;

        String sql = """
            WITH active_release AS (
                SELECT release_id FROM dataset_active_release WHERE domain = 'grammar-toeic'
            ), routes AS (
                %s
            ), ranked AS (
                SELECT q.id AS item_id, q.stem_en, q.options::text AS options_json,
                       q.correct_option, q.difficulty_level AS item_difficulty,
                       q.explanation_preference, q.annotations::text AS annotations_json,
                       r.topic_catalog_id, r.subtopic_catalog_id,
                       r.order_index, r.source_question_number,
                       row_number() OVER (
                           PARTITION BY q.id
                           ORDER BY r.route_priority, r.order_index NULLS LAST,
                                    r.source_question_number NULLS LAST
                       ) AS route_rank
                  FROM assessment_item q
                  JOIN active_release ar ON ar.release_id = q.release_id
                  JOIN routes r ON r.item_id = q.id
                 WHERE r.primary_publication_status = 'PUBLISHED'
                   AND %s
                   AND (
                       r.secondary_catalog_id IS NULL
                       OR (
                           r.secondary_publication_status = 'PUBLISHED'
                           AND %s
                       )
                   )
                   %s
            )
            SELECT item_id, stem_en, options_json, correct_option, item_difficulty,
                   explanation_preference, annotations_json, topic_catalog_id, subtopic_catalog_id
              FROM ranked
             WHERE route_rank = 1
             ORDER BY item_difficulty NULLS LAST, source_question_number NULLS LAST, item_id
             LIMIT :limit
            """.formatted(routes.sql(), featurePredicate, routes.secondaryFeaturePredicate(allowedFeatureKeys, parameters), difficultyPredicate);
        return jdbc.query(sql, parameters, questionMapper());
    }

    @Override
    public Optional<GrammarQuestion> findQuestion(UUID itemId, Set<String> allowedFeatureKeys) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("itemId", itemId);
        String featurePredicate = featurePredicate(allowedFeatureKeys, parameters);
        String sql = """
            WITH active_release AS (
                SELECT release_id FROM dataset_active_release WHERE domain = 'grammar-toeic'
            ), routes AS (
                %s
            )
            SELECT q.id AS item_id, q.stem_en, q.options::text AS options_json,
                   q.correct_option, q.difficulty_level AS item_difficulty,
                   q.explanation_preference, q.annotations::text AS annotations_json,
                   r.topic_catalog_id, r.subtopic_catalog_id
              FROM assessment_item q
              JOIN active_release ar ON ar.release_id = q.release_id
              JOIN routes r ON r.item_id = q.id
             WHERE q.id = :itemId
               AND r.primary_publication_status = 'PUBLISHED'
               AND %s
               AND (
                   r.secondary_catalog_id IS NULL
                   OR (
                       r.secondary_publication_status = 'PUBLISHED'
                       AND %s
                   )
               )
             ORDER BY r.route_priority, r.order_index NULLS LAST
             LIMIT 1
            """.formatted(routeUnion("", ""), featurePredicate, secondaryFeaturePredicate(allowedFeatureKeys, parameters));
        return jdbc.query(sql, parameters, questionMapper()).stream().findFirst();
    }

    @Override
    public UUID saveAttempt(
        UUID learnerId,
        UUID itemId,
        String submittedAnswer,
        boolean correct,
        Instant answeredAt
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update(
            """
            INSERT INTO grammar_practice_attempt(id, learner_id, item_id, answer, correct, answered_at)
            VALUES (:id, :learnerId, :itemId, :answer, :correct, :answeredAt)
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("learnerId", learnerId)
                .addValue("itemId", itemId)
                .addValue("answer", submittedAnswer)
                .addValue("correct", correct)
                .addValue("answeredAt", Timestamp.from(answeredAt))
        );
        return id;
    }

    private SqlFragment filteredRoutes(GrammarPracticeFilter filter) {
        boolean bankOnly = filter.bankCatalogId() != null;
        boolean topicOnly = filter.topicCatalogId() != null || filter.subtopicCatalogId() != null;
        String topicWhere = "";
        String bankWhere = "";
        if (filter.topicCatalogId() != null) topicWhere += " AND tv.catalog_id = :topicCatalogId";
        if (filter.subtopicCatalogId() != null) topicWhere += " AND sv.catalog_id = :subtopicCatalogId";
        if (filter.bankCatalogId() != null) bankWhere += " AND bv.catalog_id = :bankCatalogId";
        if (bankOnly) return new SqlFragment(routeUnion("AND FALSE", bankWhere), false);
        if (topicOnly) return new SqlFragment(routeUnion(topicWhere, "AND FALSE"), true);
        return new SqlFragment(routeUnion(topicWhere, bankWhere), true);
    }

    private static String routeUnion(String topicFilters, String bankFilters) {
        return """
            SELECT m.item_id,
                   tv.catalog_id AS topic_catalog_id,
                   sv.catalog_id AS subtopic_catalog_id,
                   NULL::uuid AS bank_catalog_id,
                   tc.publication_status AS primary_publication_status,
                   tc.access_mode AS primary_access_mode,
                   tc.required_feature_key AS primary_feature_key,
                   sc.publication_status AS secondary_publication_status,
                   sc.access_mode AS secondary_access_mode,
                   sc.required_feature_key AS secondary_feature_key,
                   tc.id AS primary_catalog_id,
                   sc.id AS secondary_catalog_id,
                   m.order_index,
                   m.source_question_number,
                   0 AS route_priority,
                   'topic'::text AS route_type
              FROM grammar_membership m
              JOIN active_release ar ON ar.release_id = m.release_id
              JOIN grammar_topic_version tv ON tv.id = m.topic_version_id AND tv.release_id = m.release_id
              JOIN grammar_topic_catalog tc ON tc.id = tv.catalog_id
              LEFT JOIN grammar_subtopic_version sv
                ON sv.id = m.subtopic_version_id AND sv.release_id = m.release_id
              LEFT JOIN grammar_subtopic_catalog sc ON sc.id = sv.catalog_id
             WHERE m.mode = 'topic' %s
            UNION ALL
            SELECT m.item_id,
                   NULL::uuid AS topic_catalog_id,
                   NULL::uuid AS subtopic_catalog_id,
                   bv.catalog_id AS bank_catalog_id,
                   bc.publication_status AS primary_publication_status,
                   bc.access_mode AS primary_access_mode,
                   bc.required_feature_key AS primary_feature_key,
                   NULL::varchar AS secondary_publication_status,
                   NULL::varchar AS secondary_access_mode,
                   NULL::varchar AS secondary_feature_key,
                   bc.id AS primary_catalog_id,
                   NULL::uuid AS secondary_catalog_id,
                   m.order_index,
                   m.source_question_number,
                   1 AS route_priority,
                   'bank'::text AS route_type
              FROM grammar_membership m
              JOIN active_release ar ON ar.release_id = m.release_id
              JOIN grammar_bank_version bv ON bv.id = m.bank_version_id AND bv.release_id = m.release_id
              JOIN grammar_bank_catalog bc ON bc.id = bv.catalog_id
             WHERE m.mode = 'bank' %s
            """.formatted(topicFilters, bankFilters);
    }

    private String featurePredicate(Set<String> allowedFeatureKeys, MapSqlParameterSource parameters) {
        if (allowedFeatureKeys == null || allowedFeatureKeys.isEmpty()) {
            return "r.primary_access_mode = 'PUBLIC'";
        }
        parameters.addValue("allowedFeatureKeys", allowedFeatureKeys);
        return "(r.primary_access_mode = 'PUBLIC' OR (r.primary_access_mode = 'FEATURE' AND r.primary_feature_key IN (:allowedFeatureKeys)))";
    }

    private static String secondaryFeaturePredicate(Set<String> allowedFeatureKeys, MapSqlParameterSource parameters) {
        if (allowedFeatureKeys == null || allowedFeatureKeys.isEmpty()) {
            return "r.secondary_access_mode = 'PUBLIC'";
        }
        parameters.addValue("allowedFeatureKeys", allowedFeatureKeys);
        return "(r.secondary_access_mode = 'PUBLIC' OR (r.secondary_access_mode = 'FEATURE' AND r.secondary_feature_key IN (:allowedFeatureKeys)))";
    }

    private static boolean isPublishedPath(ResultSet rs) throws SQLException {
        String primary = rs.getString("primary_publication_status");
        String secondary = rs.getString("secondary_publication_status");
        UUID secondaryCatalogId = rs.getObject("secondary_catalog_id", UUID.class);
        return "PUBLISHED".equals(primary)
            && (secondaryCatalogId == null || "PUBLISHED".equals(secondary));
    }

    private static List<String> featureKeys(ResultSet rs) throws SQLException {
        Set<String> keys = new HashSet<>();
        if ("FEATURE".equals(rs.getString("primary_access_mode"))) {
            String key = rs.getString("primary_feature_key");
            if (key != null) keys.add(key);
        }
        if (rs.getObject("secondary_catalog_id", UUID.class) != null
            && "FEATURE".equals(rs.getString("secondary_access_mode"))) {
            String key = rs.getString("secondary_feature_key");
            if (key != null) keys.add(key);
        }
        return List.copyOf(keys);
    }

    private RowMapper<GrammarQuestion> questionMapper() {
        return (rs, rowNum) -> mapQuestion(rs);
    }

    private GrammarQuestion mapQuestion(ResultSet rs) throws SQLException {
        List<Option> options = readOptions(rs.getString("options_json"));
        JsonNode annotations = readAnnotations(rs.getString("annotations_json"));
        String policy = rs.getString("explanation_preference");
        ExplanationPolicy explanationPolicy;
        try {
            explanationPolicy = ExplanationPolicy.valueOf(policy == null ? "SOURCE" : policy);
        } catch (IllegalArgumentException invalidImportedPolicy) {
            explanationPolicy = ExplanationPolicy.SOURCE;
        }

        Integer difficulty = rs.getObject("item_difficulty", Integer.class);
        return new GrammarQuestion(
            rs.getObject("item_id", UUID.class),
            rs.getString("stem_en"),
            options,
            rs.getString("correct_option"),
            annotation(annotations, "rationale_vi"),
            annotation(annotations, "content_translation_vi"),
            annotation(annotations, "option_translation_note_vi"),
            annotation(annotations, "vocabulary_note_vi"),
            difficulty == null ? 0 : difficulty,
            rs.getObject("topic_catalog_id", UUID.class),
            rs.getObject("subtopic_catalog_id", UUID.class),
            explanationPolicy
        );
    }

    private List<Option> readOptions(String value) {
        try {
            return List.copyOf(objectMapper.readValue(value, new TypeReference<List<Option>>() {}));
        } catch (JacksonException invalidOptions) {
            throw new IllegalStateException("Stored assessment item options are invalid JSON", invalidOptions);
        }
    }

    private JsonNode readAnnotations(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JacksonException invalidAnnotations) {
            throw new IllegalStateException("Stored assessment item annotations are invalid JSON", invalidAnnotations);
        }
    }

    private static String annotation(JsonNode annotations, String name) {
        JsonNode value = annotations == null ? null : annotations.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    private record SqlFragment(String sql, boolean includesTopicRoute) {
        String secondaryFeaturePredicate(Set<String> allowedFeatureKeys, MapSqlParameterSource parameters) {
            if (!includesTopicRoute) return "TRUE";
            return JdbcGrammarPracticeRepository.secondaryFeaturePredicate(allowedFeatureKeys, parameters);
        }
    }
}
