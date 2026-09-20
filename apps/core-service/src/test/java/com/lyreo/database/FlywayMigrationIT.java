package com.lyreo.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the database and testing foundation on fresh PostgreSQL:
 * 1. Testcontainers provisions real PostgreSQL 18 (alpine).
 * 2. Spring Boot Flyway auto-configuration applies the consolidated baseline (V001..V003).
 * 3. Asserts migration status, essential schema objects, indexes, constraints, and reference data.
 */
@SpringBootTest(classes = FlywayMigrationIT.TestConfig.class)
class FlywayMigrationIT {

    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");

    static {
        postgres.start();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestConfig {
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void verifiesConsolidatedBaselineMigrationsSucceeded() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied)
            .as("Three consolidated baseline migrations must be applied")
            .hasSize(3);

        assertThat(applied[0].getVersion().getVersion()).isEqualTo("001");
        assertThat(applied[0].getDescription()).isEqualTo("baseline schema");
        assertThat(applied[0].getState()).isEqualTo(MigrationState.SUCCESS);

        assertThat(applied[1].getVersion().getVersion()).isEqualTo("002");
        assertThat(applied[1].getDescription()).isEqualTo("reference data");
        assertThat(applied[1].getState()).isEqualTo(MigrationState.SUCCESS);

        assertThat(applied[2].getVersion().getVersion()).isEqualTo("003");
        assertThat(applied[2].getDescription()).isEqualTo("runtime defaults");
        assertThat(applied[2].getState()).isEqualTo(MigrationState.SUCCESS);

        assertThat(flyway.info().current().getVersion().getVersion())
            .as("Current Flyway version must be 003")
            .isEqualTo("003");
    }

    @Test
    void verifiesEssentialTablesAndHistoricalAdditionsExist() {
        List<String> tables = jdbcTemplate.queryForList(
            """
            SELECT table_name FROM information_schema.tables
            WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
            """,
            String.class
        );

        // Core infrastructure & platform
        assertThat(tables).contains(
            "event_publication",
            "app_user",
            "learner_profile",
            "background_job",
            "data_license",
            "data_source",
            "dataset_import",
            "module_runtime_config"
        );

        // AI & Lesson
        assertThat(tables).contains(
            "ai_provider",
            "ai_capability_route",
            "ai_invocation",
            "lesson",
            "lesson_sentence",
            "lesson_word_timestamp",
            "lesson_annotation",
            "lesson_activity",
            "lesson_build_job",
            "lesson_build_job_step",
            "lesson_progress",
            "lesson_practice_attempt",
            "sentence_pronunciation",
            "lesson_activity_progress"
        );

        // Lexicon, Vocabulary, Grammar, TOEIC, Curriculum, Gamification, Chat
        assertThat(tables).contains(
            "lexicon_entry",
            "lexicon_form",
            "lexicon_sense",
            "lexicon_pronunciation",
            "vocabulary_card",
            "vocabulary_review",
            "grammar_topic",
            "grammar_subtopic",
            "grammar_difficulty_level",
            "grammar_bank_set",
            "grammar_question",
            "grammar_question_membership",
            "grammar_practice_attempt",
            "toeic_mock_test",
            "toeic_passage",
            "toeic_question",
            "toeic_attempt",
            "toeic_attempt_answer",
            "speech_attempt",
            "curriculum_path",
            "curriculum_section",
            "curriculum_item",
            "curriculum_enrollment",
            "curriculum_item_progress",
            "learner_level",
            "diamond_wallet",
            "diamond_transaction",
            "mission_definition",
            "mission_progress",
            "learner_daily_activity",
            "learner_skill_summary",
            "learner_weakness",
            "chat_conversation",
            "chat_message"
        );
    }

    @Test
    void verifiesRepresentativeIndexesExist() {
        List<String> indexes = jdbcTemplate.queryForList(
            "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'",
            String.class
        );

        // Spring Modulith hash index
        assertThat(indexes).contains("event_publication_serialized_event_hash_idx");

        // Background job indexes
        assertThat(indexes).contains(
            "background_job_claim_idx",
            "background_job_owner_idx",
            "background_job_lease_idx"
        );

        // Historical additions from V007 & V008
        assertThat(indexes).contains(
            "lesson_activity_progress_activity_idx",
            "grammar_question_topic_practice_idx",
            "grammar_question_difficulty_idx"
        );
    }

    @Test
    void verifiesReferenceAndRuntimeDefaultDataSeeded() {
        // Grammar difficulty reference catalog (V002)
        Integer grammarDifficultyCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM grammar_difficulty_level",
            Integer.class
        );
        assertThat(grammarDifficultyCount).isEqualTo(5);

        // Mission definitions (V002)
        List<String> missionCodes = jdbcTemplate.queryForList(
            "SELECT code FROM mission_definition ORDER BY code",
            String.class
        );
        assertThat(missionCodes).containsExactly(
            "DAILY_GRAMMAR_5",
            "DAILY_LESSON",
            "DAILY_TOEIC",
            "DAILY_VOCAB_10"
        );

        // Lesson processing policy runtime config (V003)
        Integer runtimeConfigCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM module_runtime_config WHERE owner_module = 'lesson' AND config_key = 'processing-policy'",
            Integer.class
        );
        assertThat(runtimeConfigCount).isEqualTo(1);
    }
}
