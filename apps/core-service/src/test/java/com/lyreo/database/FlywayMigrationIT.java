package com.lyreo.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import com.lyreo.lexicon.infrastructure.persistence.JdbcLexiconRepository;
import com.lyreo.lexicon.application.LexiconSearchService;
import com.lyreo.lexicon.domain.LexiconSearchResult.MatchType;
import com.lyreo.grammar.application.GrammarPracticeFilter;
import com.lyreo.grammar.infrastructure.persistence.JdbcGrammarPracticeRepository;
import com.lyreo.toeic.infrastructure.persistence.JdbcToeicAttemptRepository;
import com.lyreo.toeic.infrastructure.persistence.JdbcToeicTestContentRepository;
import com.lyreo.entitlement.api.FeatureKey;
import com.lyreo.entitlement.application.internal.DefaultEntitlementService;
import com.lyreo.entitlement.infrastructure.persistence.JdbcFeatureRepository;
import com.lyreo.entitlement.infrastructure.persistence.JdbcUserEntitlementGrantRepository;
import com.lyreo.contracts.toeic.ToeicAttemptCompletedEvent;
import com.lyreo.gamification.application.MissionEventListener;
import com.lyreo.gamification.application.MissionProgressService;
import com.lyreo.gamification.infrastructure.persistence.JdbcDiamondLedger;
import com.lyreo.gamification.infrastructure.persistence.JdbcMissionProgressRepository;
import com.lyreo.gamification.infrastructure.GamificationConfiguration;
import com.lyreo.vocabulary.application.port.SpacedRepetitionScheduler;
import com.lyreo.vocabulary.infrastructure.persistence.JdbcVocabularyRepository;
import com.lyreo.toeic.application.port.ToeicAttemptRepository.ScoreSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves the database and testing foundation on fresh PostgreSQL:
 * 1. Testcontainers provisions real PostgreSQL 18 (alpine).
 * 2. Spring Boot Flyway applies the consolidated baseline and production cutover (V001..V005).
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
    @Import({GamificationConfiguration.class, MissionEventListener.class})
    static class TestConfig {
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void verifiesConsolidatedBaselineMigrationsSucceeded() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied)
            .as("Five migrations must be applied")
            .hasSize(5);

        assertThat(applied[0].getVersion().getVersion()).isEqualTo("001");
        assertThat(applied[0].getDescription()).isEqualTo("baseline schema");
        assertThat(applied[0].getState()).isEqualTo(MigrationState.SUCCESS);

        assertThat(applied[1].getVersion().getVersion()).isEqualTo("002");
        assertThat(applied[1].getDescription()).isEqualTo("reference data");
        assertThat(applied[1].getState()).isEqualTo(MigrationState.SUCCESS);

        assertThat(applied[2].getVersion().getVersion()).isEqualTo("003");
        assertThat(applied[2].getDescription()).isEqualTo("runtime defaults");
        assertThat(applied[2].getState()).isEqualTo(MigrationState.SUCCESS);

        assertThat(applied[3].getVersion().getVersion()).isEqualTo("004");
        assertThat(applied[3].getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(applied[4].getVersion().getVersion()).isEqualTo("005");
        assertThat(applied[4].getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(flyway.info().current().getVersion().getVersion())
            .as("Current Flyway version must be 005")
            .isEqualTo("005");
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
            "dataset_release",
            "dataset_import_run",
            "dataset_active_release",
            "dataset_activation_history",
            "entitlement_feature",
            "user_entitlement_grant",
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

        // Single-generation production content, learner state, and untouched modules.
        assertThat(tables).contains(
            "lexicon_headword",
            "lexicon_entry",
            "lexicon_item",
            "lexicon_form",
            "lexicon_sense",
            "lexicon_pronunciation",
            "lexicon_translation",
            "vocabulary_card",
            "vocabulary_review",
            "media_blob",
            "release_media_asset",
            "media_asset_use",
            "assessment_item",
            "grammar_topic_catalog",
            "grammar_topic_version",
            "grammar_subtopic_catalog",
            "grammar_subtopic_version",
            "grammar_difficulty_level",
            "grammar_difficulty_level_version",
            "grammar_bank_catalog",
            "grammar_bank_version",
            "grammar_membership",
            "grammar_practice_attempt",
            "toeic_test_catalog",
            "toeic_test_version",
            "toeic_stimulus_group",
            "toeic_document",
            "toeic_placement",
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
        assertThat(tables).doesNotContain(
            "dataset_import", "grammar_topic", "grammar_subtopic", "grammar_bank_set",
            "grammar_question", "grammar_question_membership", "toeic_mock_test",
            "toeic_passage", "toeic_question"
        );
        List<String> retiredColumns = jdbcTemplate.queryForList("""
            SELECT table_name || '.' || column_name FROM information_schema.columns
             WHERE table_schema='public' AND (
                 (table_name='lexicon_entry' AND column_name='normalized_form') OR
                 (table_name='vocabulary_card' AND column_name='lexicon_entry_id') OR
                 (table_name='grammar_practice_attempt' AND column_name='question_id') OR
                 (table_name='toeic_attempt' AND column_name='test_id') OR
                 (table_name='toeic_attempt_answer' AND column_name='question_id'))
            """, String.class);
        assertThat(retiredColumns).isEmpty();
        Integer immutableTruncateGuards = jdbcTemplate.queryForObject("""
            SELECT count(*) FROM pg_trigger
             WHERE tgname LIKE '%_snapshot_no_truncate' AND NOT tgisinternal
            """, Integer.class);
        assertThat(immutableTruncateGuards).isEqualTo(21);
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

        // Historical lesson addition and production content indexes
        assertThat(indexes).contains(
            "lesson_activity_progress_activity_idx",
            "lexicon_entry_lookup_idx",
            "lexicon_form_lookup_idx",
            "lexicon_sense_entry_idx",
            "lexicon_form_entry_idx",
            "lexicon_pronunciation_entry_idx",
            "grammar_membership_topic_idx",
            "toeic_placement_test_idx"
        );
        for (String index : List.of(
            "lexicon_sense_entry_idx",
            "lexicon_form_entry_idx",
            "lexicon_pronunciation_entry_idx"
        )) {
            String definition = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                String.class,
                index
            );
            assertThat(definition).contains("(release_id, entry_id)");
        }
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

    @Test
    void rejectsCrossDomainActivationAndCrossReleaseLexiconChild() {
        UUID lexiconRelease = UUID.randomUUID();
        UUID otherRelease = UUID.randomUUID();
        insertRelease(lexiconRelease, "lexicon", "1.0.0-test", "a".repeat(64));
        insertRelease(otherRelease, "grammar-toeic", "1.0.1-test", "b".repeat(64));
        UUID headword = UUID.randomUUID();
        UUID entry = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO lexicon_headword(id,language,identity_form) VALUES(?,'en',?)",
            headword, "test-" + headword);
        jdbcTemplate.update("""
            INSERT INTO lexicon_entry(id,release_id,headword_id,package_entry_id,display_form,lookup_form,entry_type)
            VALUES(?,?,?,?,'test','test','WORD')
            """, entry, lexiconRelease, headword, UUID.randomUUID());
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO lexicon_item(id,release_id,entry_id,package_item_id,part_of_speech,pos_title,order_index)
            VALUES(?,?,?,?, 'noun','Noun',1)
            """, UUID.randomUUID(), otherRelease, entry, UUID.randomUUID()))
            .isInstanceOf(Exception.class);
        jdbcTemplate.update("""
            INSERT INTO dataset_import_run(id,release_id,run_type,status,completed_at)
            VALUES(?,?,'APPLY','SUCCEEDED',now())
            """, UUID.randomUUID(), lexiconRelease);

        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO dataset_active_release(domain,release_id,activated_by)
            VALUES('grammar-toeic',?,'test')
            """, lexiconRelease)).isInstanceOf(Exception.class);

        jdbcTemplate.update("""
            INSERT INTO dataset_active_release(domain,release_id,activated_by)
            VALUES('lexicon',?,'test')
            ON CONFLICT(domain) DO UPDATE SET release_id=excluded.release_id, activated_by='test'
            """, lexiconRelease);
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO lexicon_item(id,release_id,entry_id,package_item_id,part_of_speech,pos_title,order_index)
            VALUES(?,?,?,?, 'noun','Noun',1)
            """, UUID.randomUUID(), lexiconRelease, entry, UUID.randomUUID()))
            .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE lexicon_entry SET display_form='changed' WHERE id=?", entry))
            .isInstanceOf(Exception.class);
    }

    @Test
    void rejectsToeicAnswerFromAnotherTestVersion() {
        UUID releaseA = UUID.randomUUID();
        UUID releaseB = UUID.randomUUID();
        insertRelease(releaseA, "grammar-toeic", "toeic-a-test", "c".repeat(64));
        insertRelease(releaseB, "grammar-toeic", "toeic-b-test", "d".repeat(64));
        UUID versionA = insertTestVersion(releaseA, 2020);
        UUID versionB = insertTestVersion(releaseB, 2021);
        UUID catalogA = jdbcTemplate.queryForObject(
            "SELECT id FROM toeic_test_catalog WHERE year=2020 AND test_number=1", UUID.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO toeic_test_version(id,release_id,catalog_id,package_test_id,name,year,test_number,
                                           set_id,total_questions,source_is_free,source_is_hidden)
            VALUES(?,?,?,?,?, 2022, 1, ?, 200, true, false)
            """, UUID.randomUUID(), releaseA, catalogA, UUID.randomUUID(), "Wrong catalog", UUID.randomUUID()))
            .isInstanceOf(Exception.class);
        UUID placementA = insertPlacement(releaseA, versionA);
        UUID placementB = insertPlacement(releaseB, versionB);
        UUID learner = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO app_user(id,keycloak_subject) VALUES(?,?)",
            learner, "test-" + learner);
        jdbcTemplate.update("""
            INSERT INTO toeic_attempt(id,learner_id,test_version_id,mode,status)
            VALUES(?, ?, ?, 'FULL_TEST', 'COMPLETED')
            """, attempt, learner, versionA);
        jdbcTemplate.update("""
            INSERT INTO toeic_attempt_answer(attempt_id,placement_id,test_version_id,answer,correct)
            VALUES(?,?,?,'A',true)
            """, attempt, placementA, versionA);
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO toeic_attempt_answer(attempt_id,placement_id,test_version_id,answer,correct)
            VALUES(?,?,?,'A',true)
            """, attempt, placementB, versionA)).isInstanceOf(Exception.class);
    }

    @Test
    void preservesDistinctGrammarBanksFromTheSameSourceYear() {
        UUID release = UUID.randomUUID();
        insertRelease(release, "grammar-toeic", "banks-test", "e".repeat(64));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        for (UUID catalog : List.of(first, second)) {
            jdbcTemplate.update("""
                INSERT INTO grammar_bank_catalog(
                    id,year,publication_status,access_mode,reconciliation_status
                ) VALUES(?,2026,'DRAFT','PUBLIC','PROVISIONAL')
                """, catalog);
            jdbcTemplate.update("""
                INSERT INTO grammar_bank_version(
                    id,release_id,catalog_id,package_bank_id,name,year,order_index,
                    source_access_level,source_counts
                ) VALUES(?,?,?,?,?,2026,0,'free','{}'::jsonb)
                """, UUID.randomUUID(), release, catalog, UUID.randomUUID(), "Bank " + catalog);
        }
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM grammar_bank_version WHERE release_id=?", Integer.class, release);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void rejectsUrlsAsMediaObjectKeys() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO media_blob(sha256,mime_type,size_bytes,storage_object_key)
            VALUES(?,'audio/mpeg',1,'s3://bucket/key')
            """, "e".repeat(64))).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO media_blob(sha256,mime_type,size_bytes,storage_object_key)
            VALUES(?,'audio/mpeg',1,'media/key?sig=abc')
            """, "f".repeat(64))).isInstanceOf(Exception.class);
    }

    @Test
    void evaluatesEntitlementExpiryAndRevocationAgainstPostgres() {
        UUID learner = UUID.randomUUID();
        UUID grant = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-27T00:00:00Z");
        jdbcTemplate.update("INSERT INTO app_user(id,keycloak_subject) VALUES(?,?)",
            learner, "grant-" + learner);
        jdbcTemplate.update("""
            INSERT INTO entitlement_feature(feature_key,display_name)
            VALUES('grammar.advanced','Advanced grammar')
            """);
        jdbcTemplate.update("""
            INSERT INTO user_entitlement_grant(
                id,user_id,feature_key,status,valid_from,valid_until,source_type,created_by
            ) VALUES(?,?,'grammar.advanced','ACTIVE',?,?, 'MANUAL','test')
            """, grant, learner, java.sql.Timestamp.from(now.minusSeconds(60)),
            java.sql.Timestamp.from(now.plusSeconds(60)));
        var named = new NamedParameterJdbcTemplate(jdbcTemplate.getDataSource());
        var service = new DefaultEntitlementService(
            new JdbcFeatureRepository(named), new JdbcUserEntitlementGrantRepository(named),
            Clock.fixed(now, ZoneOffset.UTC));
        FeatureKey key = FeatureKey.of("grammar.advanced");
        assertThat(service.hasFeature(learner, key)).isTrue();
        jdbcTemplate.update("""
            UPDATE user_entitlement_grant
               SET status='REVOKED',revoked_at=now(),revoked_by='test'
             WHERE id=?
            """, grant);
        assertThat(service.hasFeature(learner, key)).isFalse();
        jdbcTemplate.update("""
            INSERT INTO user_entitlement_grant(
                id,user_id,feature_key,status,valid_from,valid_until,source_type,created_by
            ) VALUES(?,?,'grammar.advanced','ACTIVE',?,?, 'MANUAL','test')
            """, UUID.randomUUID(), learner, java.sql.Timestamp.from(now.minusSeconds(120)),
            java.sql.Timestamp.from(now.minusSeconds(60)));
        assertThat(service.hasFeature(learner, key)).isFalse();
        assertThatThrownBy(() -> jdbcTemplate.update("""
            INSERT INTO user_entitlement_grant(
                id,user_id,feature_key,status,valid_from,valid_until,source_type,created_by
            ) VALUES(?,?,'grammar.advanced','ACTIVE',?,?, 'MANUAL','test')
            """, UUID.randomUUID(), learner, java.sql.Timestamp.from(now.plusSeconds(60)),
            java.sql.Timestamp.from(now.minusSeconds(60)))).isInstanceOf(Exception.class);
    }

    @Test
    void toeicDrillMissionWritesDiamondRewardOnlyOnce() {
        UUID learner = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO app_user(id,keycloak_subject) VALUES(?,?)",
            learner, "mission-" + learner);
        try {
            var named = new NamedParameterJdbcTemplate(jdbcTemplate.getDataSource());
            var listener = new MissionEventListener(new MissionProgressService(
                new JdbcMissionProgressRepository(named), new JdbcDiamondLedger(named)));
            var event = new ToeicAttemptCompletedEvent(
                learner, UUID.randomUUID(), UUID.randomUUID(), "DRILL",
                1, 1, 1, 1, null, null, Instant.now());

            listener.toeic(event);
            listener.toeic(event);

            assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM diamond_transaction
                 WHERE learner_id = ? AND transaction_type = 'MISSION_REWARD'
                """, Integer.class, learner)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT cached_balance FROM diamond_wallet WHERE learner_id = ?",
                Integer.class, learner)).isEqualTo(4);
            assertThat(jdbcTemplate.queryForObject("""
                SELECT progress FROM mission_progress
                 WHERE learner_id = ? AND mission_id = '10000000-0000-0000-0000-000000000003'
                """, Integer.class, learner)).isEqualTo(1);
        } finally {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", learner);
        }
    }

    @Test
    void toeicDrillMissionPublicationCompletesAndDoesNotDuplicateReward() throws InterruptedException {
        UUID learner = UUID.randomUUID();
        UUID attempt = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO app_user(id,keycloak_subject) VALUES(?,?)",
            learner, "mission-publication-" + learner);
        try {
            var event = new ToeicAttemptCompletedEvent(
                learner, attempt, UUID.randomUUID(), "DRILL",
                1, 1, 1, 1, null, null, Instant.now());
            new TransactionTemplate(transactions).executeWithoutResult(status -> events.publishEvent(event));

            String publicationStatus = null;
            for (int i = 0; i < 100; i++) {
                publicationStatus = jdbcTemplate.query("""
                    SELECT status FROM event_publication
                     WHERE serialized_event LIKE ? AND listener_id LIKE '%MissionEventListener%'
                    """, rs -> rs.next() ? rs.getString(1) : null, "%" + attempt + "%");
                if ("COMPLETED".equals(publicationStatus)) break;
                Thread.sleep(50);
            }
            assertThat(publicationStatus).isEqualTo("COMPLETED");

            new TransactionTemplate(transactions).executeWithoutResult(status -> events.publishEvent(event));
            Integer completedPublications = 0;
            for (int i = 0; i < 100; i++) {
                completedPublications = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM event_publication
                     WHERE serialized_event LIKE ? AND listener_id LIKE '%MissionEventListener%'
                       AND status = 'COMPLETED'
                    """, Integer.class, "%" + attempt + "%");
                if (completedPublications != null && completedPublications >= 2) break;
                Thread.sleep(50);
            }
            assertThat(completedPublications).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM diamond_transaction WHERE learner_id = ?
                """, Integer.class, learner)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                "SELECT cached_balance FROM diamond_wallet WHERE learner_id = ?",
                Integer.class, learner)).isEqualTo(4);
        } finally {
            jdbcTemplate.update("DELETE FROM event_publication WHERE serialized_event LIKE ?",
                "%" + attempt + "%");
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", learner);
        }
    }

    @Test
    void servesLexiconFromActiveReleaseWithHierarchy() {
        UUID release = UUID.randomUUID();
        UUID headword = UUID.randomUUID();
        UUID entry = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        UUID sense = UUID.randomUUID();
        insertRelease(release, "lexicon", "smoke-" + release.toString().substring(0, 8),
            release.toString().replace("-", "").repeat(2));
        jdbcTemplate.update("INSERT INTO lexicon_headword(id,language,identity_form) VALUES(?,'en',?)",
            headword, "smoke-" + headword);
        jdbcTemplate.update("""
            INSERT INTO lexicon_entry(id,release_id,headword_id,package_entry_id,display_form,lookup_form,entry_type)
            VALUES(?,?,?,?,'smokeword','smokeword','WORD')
            """, entry, release, headword, UUID.randomUUID());
        jdbcTemplate.update("""
            INSERT INTO lexicon_item(id,release_id,entry_id,package_item_id,part_of_speech,pos_title,order_index)
            VALUES(?,?,?,?,'noun','Noun',1)
            """, item, release, entry, UUID.randomUUID());
        jdbcTemplate.update("""
            INSERT INTO lexicon_sense(id,release_id,entry_id,item_id,package_sense_id,position,
                                      definition_en,translation_status)
            VALUES(?,?,?,?,?,1,'a smoke test','MISSING')
            """, sense, release, entry, item, UUID.randomUUID());
        jdbcTemplate.update("""
            INSERT INTO dataset_import_run(id,release_id,run_type,status,completed_at)
            VALUES(?,?,'APPLY','SUCCEEDED',now())
            """, UUID.randomUUID(), release);
        jdbcTemplate.update("""
            INSERT INTO dataset_active_release(domain,release_id,activated_by)
            VALUES('lexicon',?,'test')
            ON CONFLICT(domain) DO UPDATE SET release_id=excluded.release_id, activated_by='test'
            """, release);
        var repository = new JdbcLexiconRepository(new NamedParameterJdbcTemplate(jdbcTemplate.getDataSource()));
        var found = repository.findById(entry).orElseThrow();
        assertThat(found.headwordId()).isEqualTo(headword);
        assertThat(found.items()).hasSize(1);
        assertThat(found.senses()).hasSize(1);
        assertThat(repository.search("smoke", "smoke", 10).results())
            .extracting("entryId").contains(entry);

        UUID learner = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO app_user(id,keycloak_subject) VALUES(?,?)",
            learner, "vocabulary-" + learner);
        var vocabulary = new JdbcVocabularyRepository(new NamedParameterJdbcTemplate(jdbcTemplate.getDataSource()));
        Instant now = Instant.parse("2026-09-27T00:00:00Z");
        var card = vocabulary.addIfAbsent(learner, headword, "LESSON", UUID.randomUUID(), now);
        assertThat(card.headwordId()).isEqualTo(headword);
        assertThat(vocabulary.due(learner, now, 10)).extracting("id").contains(card.id());
        vocabulary.applyReview(card.id(), SpacedRepetitionScheduler.Rating.GOOD,
            new SpacedRepetitionScheduler.ScheduleResult(now.plusSeconds(86400), 2.0, 4.0), now);
        assertThat(vocabulary.due(learner, now, 10)).isEmpty();
    }

    @Test
    void lexiconSearchRanksExactFormsBeforeNormalizedHeadwordsAndStaysOnActiveRelease() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            UUID release = UUID.randomUUID();
            UUID otherRelease = UUID.randomUUID();
            jdbcTemplate.update("""
                INSERT INTO dataset_release(id,domain,package_version,schema_version,checksum_sha256,
                                            manifest_json,license_text)
                VALUES(?,'lexicon',?,'1.0.0',?,
                       '{"source":{"name":"fixture"}}'::jsonb,'fixture license')
                """, release, "search-" + release.toString().substring(0, 8), "f".repeat(64));
            insertRelease(otherRelease, "lexicon",
                "search-" + otherRelease.toString().substring(0, 8), "0".repeat(64));

            LexiconSearchFixture apple = insertLexiconSearchEntry(release, "apple", "apple", "noun");
            insertLexiconSearchItem(release, apple.id(), "verb");
            insertLexiconSearchForm(release, apple, "apple", "apple");
            LexiconSearchFixture capitalApple = insertLexiconSearchEntry(release, "Apple", "apple", "name");
            insertLexiconSearchEntry(release, "appling", "appling", "noun");
            insertLexiconSearchEntry(release, "Appling", "appling", "name");
            insertLexiconSearchEntry(release, "appling up", "appling up", "verb");
            insertLexiconSearchForm(release, apple, "appling", "appling");
            LexiconSearchFixture went = insertLexiconSearchEntry(release, "went", "went", "verb");
            insertLexiconSearchEntry(release, "Went", "went", "name");
            LexiconSearchFixture go = insertLexiconSearchEntry(release, "go", "go", "verb");
            insertLexiconSearchForm(release, go, "went", "went");
            insertLexiconSearchForm(release, go, "went", "went");
            for (int index = 0; index < 30; index++) {
                String prefix = "wentprefix" + index;
                insertLexiconSearchEntry(release, prefix, prefix, "noun");
            }
            insertLexiconSearchEntry(release, "running", "running", "noun");
            insertLexiconSearchEntry(release, "Running", "running", "name");
            LexiconSearchFixture run = insertLexiconSearchEntry(release, "run", "run", "verb");
            insertLexiconSearchForm(release, run, "running", "running");
            insertLexiconSearchEntry(release, "children", "children", "noun");
            insertLexiconSearchEntry(release, "Children", "children", "name");
            LexiconSearchFixture child = insertLexiconSearchEntry(release, "child", "child", "noun");
            insertLexiconSearchForm(release, child, "children", "children");
            LexiconSearchFixture bergstrasse = insertLexiconSearchEntry(
                release, "Bergstraße", "bergstrasse", "noun");
            LexiconSearchFixture firstTwin = insertLexiconSearchEntry(release, "twin", "twin", "noun");
            LexiconSearchFixture secondTwin = insertLexiconSearchEntry(release, "twin", "twin", "verb");
            LexiconSearchFixture repeatedForm = insertLexiconSearchEntry(
                release, "unrelated", "unrelated", "noun");
            LexiconSearchFixture nextForm = insertLexiconSearchEntry(
                release, "second candidate", "second candidate", "noun");
            LexiconSearchFixture lastForm = insertLexiconSearchEntry(
                release, "third candidate", "third candidate", "noun");
            for (int index = 0; index < 50; index++) {
                insertLexiconSearchForm(release, repeatedForm, "zzzzformx", "zzzzformx");
            }
            insertLexiconSearchForm(release, nextForm, "zzzzformy", "zzzzformy");
            insertLexiconSearchForm(release, lastForm, "zzzzformz", "zzzzformz");
            UUID sense = UUID.randomUUID();
            jdbcTemplate.update("""
                INSERT INTO lexicon_sense(id,release_id,entry_id,item_id,package_sense_id,
                                          position,definition_en,translation_status)
                VALUES(?,?,?,?,?,1,'fruit','MISSING')
                """, sense, release, apple.id(), apple.itemId(), UUID.randomUUID());
            LexiconSearchFixture shadow = insertLexiconSearchEntry(
                otherRelease, "wentshadow", "wentshadow", "noun");

            for (UUID importedRelease : List.of(release, otherRelease)) {
                jdbcTemplate.update("""
                    INSERT INTO dataset_import_run(id,release_id,run_type,status,completed_at)
                    VALUES(?,?,'APPLY','SUCCEEDED',now())
                    """, UUID.randomUUID(), importedRelease);
            }

            jdbcTemplate.update("""
                INSERT INTO dataset_active_release(domain,release_id,activated_by)
                VALUES('lexicon',?,'test')
                ON CONFLICT(domain) DO UPDATE SET release_id=excluded.release_id, activated_by='test'
                """, release);
            var repository = new JdbcLexiconRepository(
                new NamedParameterJdbcTemplate(jdbcTemplate.getDataSource()));
            var search = new LexiconSearchService(repository);

            var apples = search.search("apple", 20);
            assertThat(apples.results()).extracting("headword").startsWith("apple", "Apple");
            assertThat(apples.results().getFirst().entryId()).isEqualTo(apple.id());
            assertThat(apples.results().getFirst().partsOfSpeech()).containsExactly("noun", "verb");
            assertThat(apples.results().get(1).entryId()).isEqualTo(capitalApple.id());
            assertThat(apples.results()).filteredOn(hit -> hit.entryId().equals(apple.id())).hasSize(1);
            assertThat(apples.licenseText()).isEqualTo("fixture license");
            var twins = search.search("twin", 20);
            assertThat(twins.results()).extracting("entryId")
                .containsExactlyInAnyOrder(firstTwin.id(), secondTwin.id());
            assertThat(search.search("twin", 20)).isEqualTo(twins);

            var wents = search.search("went", 20);
            assertThat(wents.results()).extracting("headword").startsWith("went", "go", "Went");
            assertThat(wents.results().getFirst().entryId()).isEqualTo(went.id());
            assertThat(wents.results().get(1).matchType()).isEqualTo(MatchType.EXACT_FORM);
            assertThat(wents.results().get(1).matchedText()).isEqualTo("went");
            assertThat(wents.results()).filteredOn(hit -> hit.entryId().equals(go.id())).hasSize(1);
            assertThat(search.search("went", 20)).isEqualTo(wents);

            var appling = search.search("appling", 20);
            assertThat(appling.results()).extracting("headword")
                .startsWith("appling", "apple", "Appling", "appling up");
            assertThat(appling.results().get(1).entryId()).isEqualTo(apple.id());
            assertThat(appling.results().get(1).matchedText()).isEqualTo("appling");
            assertThat(appling.results()).extracting("entryId").doesNotHaveDuplicates();
            assertThat(search.search("running", 20).results())
                .extracting("headword").startsWith("running", "run", "Running");
            assertThat(search.search("children", 20).results())
                .extracting("headword").startsWith("children", "child", "Children");
            assertThat(search.search("Bergstraße", 20).results())
                .extracting("entryId").contains(bergstrasse.id());

            var one = search.search("went", 1);
            assertThat(one.results()).hasSize(1);
            assertThat(one.hasMore()).isTrue();
            assertThat(search.search("Bergstraße", 20).hasMore()).isFalse();
            var formPage = search.search("zzzzform", 1);
            assertThat(formPage.results()).extracting("entryId").containsExactly(repeatedForm.id());
            assertThat(formPage.hasMore()).isTrue();
            assertThat(search.search("zzzzform", 20).results()).extracting("entryId")
                .containsExactly(repeatedForm.id(), nextForm.id(), lastForm.id());
            assertThat(repository.findById(apple.id()).orElseThrow().senses())
                .extracting("definitionEn").containsExactly("fruit");

            jdbcTemplate.update("UPDATE dataset_active_release SET release_id=? WHERE domain='lexicon'",
                otherRelease);
            assertThat(search.search("went", 20).results()).extracting("entryId")
                .containsExactly(shadow.id());
            assertThat(repository.findById(apple.id())).isEmpty();
            status.setRollbackOnly();
        });
    }

    @Test
    void lexiconExactFormRanksExplicitUsageMarkersWithoutPenalizingMixedForms() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            UUID release = UUID.randomUUID();
            insertRelease(release, "lexicon", "usage-" + release.toString().substring(0, 8),
                release.toString().replace("-", "").repeat(2));
            // Tags and relations reproduce lexicon-1.0.0 records, not inferred popularity.
            insertLexiconSearchEntry(release, "albe", "albe", "noun");
            LexiconSearchFixture alb = insertLexiconSearchEntry(release, "alb", "alb", "noun");
            LexiconSearchFixture albeit = insertLexiconSearchEntry(release, "albeit", "albeit", "conj");
            insertLexiconSearchForm(release, alb, "albe", "albe", "[\"alternative\",\"obsolete\"]");
            insertLexiconSearchForm(release, albeit, "albe", "albe", "[\"alternative\"]");
            insertLexiconSearchEntry(release, "went", "went", "verb");
            LexiconSearchFixture go = insertLexiconSearchEntry(release, "go", "go", "verb");
            LexiconSearchFixture gan = insertLexiconSearchEntry(release, "gan", "gan", "verb");
            insertLexiconSearchForm(release, go, "went", "went", "[\"past\"]");
            insertLexiconSearchForm(release, go, "went", "went",
                "[\"nonstandard\",\"participle\",\"past\"]");
            insertLexiconSearchForm(release, gan, "went", "went", "[\"past\"]");
            for (String[] sourceCase : List.of(
                new String[]{"ran", "rin", "run", "dialectal", "verb", "[\"past\"]"},
                new String[]{"took", "tek", "take", "dated", "verb", "[\"past\"]"},
                new String[]{"made", "mak", "make", "dialectal", "verb", "[\"past\"]"},
                new String[]{"better", "we'll", "well", "misspelling", "adv", "[\"comparative\"]"}
            )) {
                String query = sourceCase[0];
                insertLexiconSearchEntry(release, query, query, sourceCase[4]);
                LexiconSearchFixture restricted = insertLexiconSearchEntry(
                    release, sourceCase[1], sourceCase[1], sourceCase[4]);
                LexiconSearchFixture mixed = insertLexiconSearchEntry(
                    release, sourceCase[2], sourceCase[2], sourceCase[4]);
                insertLexiconSearchForm(release, restricted, query, query, sourceCase[5]);
                insertLexiconSearchForm(release, mixed, query, query, sourceCase[5]);
                insertLexiconSearchSense(release, restricted, "[\"" + sourceCase[3] + "\"]");
                insertLexiconSearchSense(release, mixed, "[]");
                insertLexiconSearchSense(release, mixed, "[\"obsolete\"]");
                UUID unmarkedOtherItem = insertLexiconSearchItem(release, restricted.id(), "noun");
                insertLexiconSearchSense(release,
                    new LexiconSearchFixture(restricted.id(), unmarkedOtherItem), "[]");
                // An unrelated restricted item must not taint the matching mixed item.
                UUID otherItem = insertLexiconSearchItem(release, mixed.id(), "noun");
                insertLexiconSearchSense(release, new LexiconSearchFixture(mixed.id(), otherItem),
                    "[\"archaic\"]");
            }
            LexiconSearchFixture bettor = insertLexiconSearchEntry(release, "bettor", "bettor", "noun");
            insertLexiconSearchForm(release, bettor, "better", "better", "[\"alternative\"]");
            jdbcTemplate.update("""
                INSERT INTO dataset_import_run(id,release_id,run_type,status,completed_at)
                VALUES(?,?,'APPLY','SUCCEEDED',now())
                """, UUID.randomUUID(), release);
            jdbcTemplate.update("""
                INSERT INTO dataset_active_release(domain,release_id,activated_by)
                VALUES('lexicon',?,'test')
                ON CONFLICT(domain) DO UPDATE SET release_id=excluded.release_id, activated_by='test'
                """, release);
            var search = new LexiconSearchService(new JdbcLexiconRepository(
                new NamedParameterJdbcTemplate(jdbcTemplate.getDataSource())));

            assertThat(search.search("albe", 20).results()).extracting("headword")
                .containsExactly("albe", "albeit", "alb");
            assertThat(search.search("ALBE", 20).results()).extracting("headword")
                .containsExactly("albe", "albeit", "alb");
            var wents = search.search("went", 20);
            assertThat(wents.results()).extracting("headword").containsExactly("went", "go", "gan");
            assertThat(wents.results().get(1).matchedText()).isEqualTo("went");
            assertThat(wents.results()).extracting("entryId").doesNotHaveDuplicates();
            for (String[] sourceCase : List.of(
                new String[]{"ran", "run", "rin"}, new String[]{"took", "take", "tek"},
                new String[]{"made", "make", "mak"}
            )) {
                assertThat(search.search(sourceCase[0], 20).results()).extracting("headword")
                    .containsExactly(sourceCase[0], sourceCase[1], sourceCase[2]);
                assertThat(search.search(sourceCase[0].toUpperCase(java.util.Locale.ROOT), 20).results())
                    .extracting("headword").containsExactly(sourceCase[0], sourceCase[1], sourceCase[2]);
            }
            assertThat(search.search("better", 20).results()).extracting("headword")
                .containsExactly("better", "well", "bettor", "we'll");
            assertThat(search.search("BETTER", 20).results()).extracting("headword")
                .containsExactly("better", "well", "bettor", "we'll");
            assertThat(search.search("albe", 1).hasMore()).isTrue();
            status.setRollbackOnly();
        });
    }

    private LexiconSearchFixture insertLexiconSearchEntry(
        UUID release, String displayForm, String lookupForm, String partOfSpeech
    ) {
        UUID headword = UUID.randomUUID();
        UUID entry = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO lexicon_headword(id,language,identity_form) VALUES(?,'en',?)",
            headword, release + ":" + displayForm + ":" + headword);
        jdbcTemplate.update("""
            INSERT INTO lexicon_entry(id,release_id,headword_id,package_entry_id,
                                      display_form,lookup_form,entry_type)
            VALUES(?,?,?,?,?,?,'WORD')
            """, entry, release, headword, UUID.randomUUID(), displayForm, lookupForm);
        return new LexiconSearchFixture(entry,
            insertLexiconSearchItem(release, entry, partOfSpeech));
    }

    private UUID insertLexiconSearchItem(UUID release, UUID entry, String partOfSpeech) {
        UUID item = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO lexicon_item(id,release_id,entry_id,package_item_id,
                                     part_of_speech,pos_title,order_index)
            VALUES(?,?,?,?,?,?,1)
            """, item, release, entry, UUID.randomUUID(), partOfSpeech, partOfSpeech);
        return item;
    }

    private void insertLexiconSearchForm(UUID release, LexiconSearchFixture entry,
                                         String form, String normalizedForm) {
        insertLexiconSearchForm(release, entry, form, normalizedForm, "[]");
    }

    private void insertLexiconSearchForm(UUID release, LexiconSearchFixture entry,
                                         String form, String normalizedForm, String tagsJson) {
        jdbcTemplate.update("""
            INSERT INTO lexicon_form(id,release_id,entry_id,item_id,package_form_id,
                                     form,normalized_form,tags)
            VALUES(?,?,?,?,?,?,?,?::jsonb)
            """, UUID.randomUUID(), release, entry.id(), entry.itemId(), UUID.randomUUID(),
            form, normalizedForm, tagsJson);
    }

    private record LexiconSearchFixture(UUID id, UUID itemId) {}

    private void insertLexiconSearchSense(UUID release, LexiconSearchFixture entry, String tagsJson) {
        jdbcTemplate.update("""
            INSERT INTO lexicon_sense(id,release_id,entry_id,item_id,package_sense_id,
                                      position,definition_en,translation_status,tags)
            VALUES(?,?,?,?,?,1,'fixture sense','MISSING',?::jsonb)
            """, UUID.randomUUID(), release, entry.id(), entry.itemId(), UUID.randomUUID(), tagsJson);
    }

    @Test
    void servesGrammarAndToeicFromActiveRelease() {
        UUID release = UUID.randomUUID();
        insertRelease(release, "grammar-toeic", "smoke-" + release.toString().substring(0, 8),
            release.toString().replace("-", "").repeat(2));
        UUID item = UUID.randomUUID();
        UUID topicCatalog = UUID.randomUUID();
        UUID topicVersion = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO assessment_item(id,release_id,package_item_id,kind,stem_en,options,correct_option,annotations)
            VALUES(?,?,?,'MULTIPLE_CHOICE','Choose yes','[{"key":"A","text":"yes"}]'::jsonb,'A',
                   '{"rationale_vi":"Đúng"}'::jsonb)
            """, item, release, UUID.randomUUID());
        jdbcTemplate.update("""
            INSERT INTO grammar_topic_catalog(id,publication_status,access_mode,reconciliation_status)
            VALUES(?,'PUBLISHED','PUBLIC','CONFIRMED')
            """, topicCatalog);
        jdbcTemplate.update("""
            INSERT INTO grammar_topic_version(id,release_id,catalog_id,package_topic_id,title_en,title_vi,
                                              order_index,source_access_level,source_is_hidden)
            VALUES(?,?,?,?, 'Modal verbs','Động từ tình thái',1,'free',false)
            """, topicVersion, release, topicCatalog, UUID.randomUUID());
        jdbcTemplate.update("""
            INSERT INTO grammar_membership(id,release_id,package_membership_id,item_id,mode,
                                           topic_version_id,order_index,source_url)
            VALUES(?,?,?,?, 'topic', ?,1,'https://source.example/grammar')
            """, UUID.randomUUID(), release, "topic:" + item, item, topicVersion);
        UUID testVersion = insertTestVersion(release, 2040);
        UUID placement = insertPlacement(release, testVersion);
        UUID group = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        String blobHash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        String mediaKey = "media/sha256/" + blobHash.substring(0, 2) + "/" + blobHash;
        jdbcTemplate.update("""
            INSERT INTO toeic_stimulus_group(id,release_id,test_version_id,package_group_id,part,
                                             kind,question_numbers,document_count)
            VALUES(?,?,?,?,3,'AUDIO',ARRAY[1],1)
            """, group, release, testVersion, UUID.randomUUID());
        jdbcTemplate.update("""
            INSERT INTO toeic_document(id,release_id,group_id,package_doc_id,ordinal,html)
            VALUES(?,?,?,? ,1,'<p>passage</p>')
            """, document, release, group, "doc:" + document);
        jdbcTemplate.update("""
            INSERT INTO media_blob(sha256,mime_type,size_bytes,storage_object_key)
            VALUES(?,'audio/mpeg',12,?)
            """, blobHash, mediaKey);
        jdbcTemplate.update("""
            INSERT INTO release_media_asset(id,release_id,package_asset_id,blob_sha256,package_path,source_paths)
            VALUES(?,?,?,?, 'media/clip.mp3','[]'::jsonb)
            """, UUID.randomUUID(), release, blobHash, blobHash);
        jdbcTemplate.update("""
            INSERT INTO media_asset_use(id,release_id,package_use_id,asset_id,role,group_id)
            VALUES(?,?,?,?, 'audio',?)
            """, UUID.randomUUID(), release, "use:" + group, blobHash, group);
        jdbcTemplate.update("""
            INSERT INTO dataset_import_run(id,release_id,run_type,status,completed_at)
            VALUES(?,?,'APPLY','SUCCEEDED',now())
            """, UUID.randomUUID(), release);
        jdbcTemplate.update("""
            INSERT INTO dataset_active_release(domain,release_id,activated_by)
            VALUES('grammar-toeic',?,'test')
            ON CONFLICT(domain) DO UPDATE SET release_id=excluded.release_id, activated_by='test'
            """, release);

        var named = new NamedParameterJdbcTemplate(jdbcTemplate.getDataSource());
        var grammar = new JdbcGrammarPracticeRepository(named, new ObjectMapper());
        var questions = grammar.findPracticeQuestions(
            new GrammarPracticeFilter(topicCatalog, null, null, null), Set.of(), 10);
        assertThat(questions).extracting("itemId").contains(item);
        assertThat(grammar.findQuestion(item, Set.of())).isPresent();

        var toeic = new JdbcToeicAttemptRepository(named);
        UUID testCatalog = jdbcTemplate.queryForObject(
            "SELECT catalog_id FROM toeic_test_version WHERE id=?", UUID.class, testVersion);
        assertThat(toeic.findActiveTest(testCatalog)).isPresent();
        var content = new JdbcToeicTestContentRepository(named, new ObjectMapper())
            .findActiveTestContent(testCatalog, testVersion).orElseThrow();
        assertThat(content.groups()).hasSize(1);
        assertThat(content.groups().getFirst().documents()).hasSize(1);
        assertThat(content.groups().getFirst().media().getFirst().storageObjectKey()).isEqualTo(mediaKey);
        assertThat(content.placements()).hasSize(1);
        assertThat(content.placements().getFirst().item().stemEn()).isNull();
        assertThat(content.placements().getFirst().item().options().getFirst().text()).isEmpty();
        var key = toeic.answerKey(testVersion, Set.of(placement));
        assertThat(key).hasSize(1);
        UUID learner = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO app_user(id,keycloak_subject) VALUES(?,?)",
            learner, "smoke-" + learner);
        UUID grammarAttempt = grammar.saveAttempt(learner, item, "A", true, Instant.now());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM grammar_practice_attempt WHERE id=? AND item_id=?",
            Integer.class, grammarAttempt, item)).isEqualTo(1);
        UUID attempt = toeic.saveCompletedAttempt(learner, testVersion, "FULL_TEST",
            new ScoreSummary(1, 1, 0, 0, null, null), Map.of(placement, "A"), key);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM toeic_attempt_answer WHERE attempt_id=? AND test_version_id=?",
            Integer.class, attempt, testVersion)).isEqualTo(1);
    }

    private UUID insertTestVersion(UUID releaseId, int year) {
        UUID catalog = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO toeic_test_catalog(id,year,test_number,publication_status,access_mode,reconciliation_status)
            VALUES(?, ?, 1, 'PUBLISHED', 'PUBLIC', 'CONFIRMED')
            """, catalog, year);
        jdbcTemplate.update("""
            INSERT INTO toeic_test_version(id,release_id,catalog_id,package_test_id,name,year,test_number,
                                           set_id,total_questions,source_is_free,source_is_hidden)
            VALUES(?,?,?,?,?, ?, 1, ?, 200, true, false)
            """, version, releaseId, catalog, UUID.randomUUID(), "Test", year, UUID.randomUUID());
        return version;
    }

    private UUID insertPlacement(UUID releaseId, UUID versionId) {
        UUID item = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO assessment_item(id,release_id,package_item_id,kind,stem_en,options,correct_option)
            VALUES(?,?,?,'MULTIPLE_CHOICE','Spoken question',
                   '[{"key":"A","text":"Spoken choice"}]'::jsonb,'A')
            """, item, releaseId, UUID.randomUUID());
        jdbcTemplate.update("""
            INSERT INTO toeic_placement(id,release_id,package_placement_id,test_version_id,item_id,
                                        section,part,question_number,order_index)
            VALUES(?,?,?,?,?,'listening',1,1,1)
            """, placement, releaseId, UUID.randomUUID(), versionId, item);
        return placement;
    }

    private void insertRelease(UUID id, String domain, String version, String checksum) {
        jdbcTemplate.update("""
            INSERT INTO dataset_release(id,domain,package_version,schema_version,checksum_sha256,manifest_json)
            VALUES(?,?,?,'1.0.0',?,'{}'::jsonb)
            """, id, domain, version, checksum);
    }
}
