package com.lyreo.platform.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class DatabaseSetupIT {
    @Test
    void seedsBothLearnerStatesAndPreservesDemoEditsOnRepeat(@TempDir Path tmp) throws Exception {
        try (var postgres = new PostgreSQLContainer("postgres:18.6-alpine")) {
            postgres.start();
            var datasource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var seed = Files.writeString(tmp.resolve("learners.sql"),
                Files.readString(Path.of("../../infra/postgres/seed/learners.sql"))
                    .replace("__MINH_ANH_SUBJECT__", "5352d996-50af-4498-a53c-9e4bd3712c75")
                    .replace("__GIA_HUY_SUBJECT__", "e963ecc1-d469-4938-94cb-c85c24e08f5d")
                    .replace("__NEW_LEARNER_SUBJECT__", "b50746c8-525c-4b37-b0f8-80cb4a494441"));
            var jdbc = new JdbcTemplate(datasource);
            DatabaseSetup.apply(datasource, null);
            jdbc.update("""
                INSERT INTO app_user (id,keycloak_subject,email_snapshot)
                VALUES ('30000000-0000-0000-0000-000000000001','demo-learner-minh-anh','minh-anh@example.test')
                """);
            jdbc.update("""
                INSERT INTO learner_profile (learner_id,display_name,daily_minutes,first_seen_at,onboarding_completed_at)
                VALUES ('30000000-0000-0000-0000-000000000001','Minh Anh',15,now(),now())
                """);
            DatabaseSetup.apply(datasource, seed);
            assertThat(jdbc.queryForObject("""
                SELECT keycloak_subject FROM app_user WHERE id = '30000000-0000-0000-0000-000000000001'
                """, String.class)).isEqualTo("5352d996-50af-4498-a53c-9e4bd3712c75");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM learner_profile WHERE first_seen_at IS NOT NULL", Integer.class))
                .isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM learner_profile WHERE onboarding_completed_at IS NOT NULL", Integer.class))
                .isEqualTo(2);
            DatabaseSetup.apply(datasource, seed);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT daily_minutes FROM learner_profile WHERE display_name = 'Minh Anh'", Integer.class))
                .isEqualTo(15);

            String newMinhAnhSubject = "cdba0081-1dbf-4e1a-b351-85bbad05e639";
            String newGiaHuySubject = "e30dc993-8b1b-4fe8-8832-6774ac70ad10";
            jdbc.update("""
                INSERT INTO app_user (id, keycloak_subject, email_snapshot)
                VALUES (gen_random_uuid(), ?, 'gia-huy@example.test')
                """, newGiaHuySubject);
            jdbc.update("""
                INSERT INTO learner_profile (learner_id, display_name, daily_minutes, first_seen_at)
                SELECT id, 'Huy JIT', 30, now() FROM app_user WHERE keycloak_subject = ?
                """, newGiaHuySubject);
            var reseed = Files.writeString(tmp.resolve("recreated-realm-learners.sql"), Files.readString(seed)
                .replace("5352d996-50af-4498-a53c-9e4bd3712c75", newMinhAnhSubject)
                .replace("e963ecc1-d469-4938-94cb-c85c24e08f5d", newGiaHuySubject));
            DatabaseSetup.apply(datasource, reseed);
            assertThat(jdbc.queryForObject("""
                SELECT p.daily_minutes FROM learner_profile p JOIN app_user u ON u.id = p.learner_id
                WHERE u.keycloak_subject = ? AND p.display_name = 'Minh Anh' AND p.onboarding_completed_at IS NOT NULL
                """, Integer.class, newMinhAnhSubject)).isEqualTo(15);
            assertThat(jdbc.queryForObject("""
                SELECT p.daily_minutes FROM learner_profile p JOIN app_user u ON u.id = p.learner_id
                WHERE u.keycloak_subject = ? AND p.display_name = 'Huy JIT'
                """, Integer.class, newGiaHuySubject)).isEqualTo(30);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM learner_profile WHERE first_seen_at IS NOT NULL", Integer.class))
                .isEqualTo(3);
        }
    }

    @Test
    void seedsTheSelectedDatabaseAndPreservesEditsWhenRepeated() {
        try (var postgres = new PostgreSQLContainer("postgres:18.6-alpine")) {
            postgres.start();
            var datasource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            var seed = Path.of("../../infra/postgres/seed/dev.sql");
            DatabaseSetup.apply(datasource, seed);
            var jdbc = new JdbcTemplate(datasource);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM lesson_category", Integer.class)).isEqualTo(24);
            assertThat(jdbc.queryForList("""
                SELECT code FROM ai_provider WHERE enabled ORDER BY code
                """, String.class)).containsExactly("LOCAL_KOKORO", "LOCAL_NLP", "LOCAL_WHISPERX");
            assertThat(jdbc.queryForList("""
                SELECT r.capability || ':' || r.model FROM ai_capability_route r
                JOIN ai_provider p ON p.id=r.provider_id
                WHERE p.code='LOCAL_WHISPERX' AND r.enabled AND r.priority=1 ORDER BY r.capability
                """, String.class)).containsExactly("ALIGNMENT:WAV2VEC2_ASR_BASE_960H", "STT:small.en");
            assertThat(jdbc.queryForList("""
                SELECT p.code || ':' || r.model FROM ai_capability_route r JOIN ai_provider p ON p.id=r.provider_id
                WHERE r.capability='GENERAL_LLM' AND r.enabled AND NOT p.enabled
                  AND p.encrypted_api_key IS NULL AND p.key_last4 IS NULL ORDER BY r.priority
                """, String.class)).containsExactly("DEEPSEEK:deepseek-flash", "GEMINI:gemini-3.5-flash-lite", "GROQ:openai/gpt-oss-120b");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_capability_route", Integer.class)).isEqualTo(8);
            var azureRoute = """
                SELECT r.enabled FROM ai_capability_route r JOIN ai_provider p ON p.id=r.provider_id
                WHERE p.code='AZURE_SPEECH' AND r.capability='PRONUNCIATION_ASSESSMENT'
                  AND r.model='pronunciation-assessment' AND r.priority=0 AND NOT r.is_fallback
                """;
            assertThat(jdbc.queryForObject("""
                SELECT enabled OR encrypted_api_key IS NOT NULL FROM ai_provider WHERE code='AZURE_SPEECH'
                """, Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject(azureRoute, Boolean.class)).isFalse();
            jdbc.update("UPDATE lesson_category SET name='Edited category' WHERE id='20000000-0000-0000-0000-000000000001'");
            jdbc.update("UPDATE ai_provider SET enabled=true,encrypted_api_key='test-ciphertext' WHERE code='DEEPSEEK'");
            jdbc.update("UPDATE ai_capability_route SET priority=17,enabled=false WHERE model='deepseek-flash'");
            jdbc.update("UPDATE ai_provider SET enabled=true,encrypted_api_key='azure-test-ciphertext' WHERE code='AZURE_SPEECH'");
            jdbc.update("UPDATE ai_capability_route SET enabled=true WHERE capability='PRONUNCIATION_ASSESSMENT'");
            jdbc.update("INSERT INTO ai_provider(id, code, display_name) VALUES (gen_random_uuid(), 'QWEN', 'Legacy')");
            jdbc.update("""
                INSERT INTO ai_capability_route(id, provider_id, capability, model)
                SELECT gen_random_uuid(), id, 'STT', 'legacy' FROM ai_provider WHERE code='QWEN'
                """);
            DatabaseSetup.apply(datasource, seed);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM lesson_category", Integer.class)).isEqualTo(24);
            assertThat(jdbc.queryForObject("SELECT name FROM lesson_category WHERE id='20000000-0000-0000-0000-000000000001'", String.class)).isEqualTo("Edited category");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_capability_route", Integer.class)).isEqualTo(8);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_provider WHERE code='QWEN'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT encrypted_api_key FROM ai_provider WHERE code='DEEPSEEK' AND enabled", String.class)).isEqualTo("test-ciphertext");
            assertThat(jdbc.queryForObject("SELECT priority FROM ai_capability_route WHERE model='deepseek-flash' AND NOT enabled", Integer.class)).isEqualTo(17);
            assertThat(jdbc.queryForObject(azureRoute, Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT encrypted_api_key FROM ai_provider WHERE code='AZURE_SPEECH' AND enabled", String.class))
                .isEqualTo("azure-test-ciphertext");
            assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .containsExactly("001", "002", "003", "004", "005", "006", "007", "008", "009");
        }
    }
}
