package com.lyreo.platform.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class DatabaseSetupIT {
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
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_capability_route", Integer.class)).isEqualTo(7);
            jdbc.update("UPDATE lesson_category SET name='Edited category' WHERE id='20000000-0000-0000-0000-000000000001'");
            jdbc.update("UPDATE ai_provider SET enabled=true,encrypted_api_key='test-ciphertext' WHERE code='DEEPSEEK'");
            jdbc.update("UPDATE ai_capability_route SET priority=17,enabled=false WHERE model='deepseek-flash'");
            jdbc.update("INSERT INTO ai_provider(id, code, display_name) VALUES (gen_random_uuid(), 'QWEN', 'Legacy')");
            jdbc.update("""
                INSERT INTO ai_capability_route(id, provider_id, capability, model)
                SELECT gen_random_uuid(), id, 'STT', 'legacy' FROM ai_provider WHERE code='QWEN'
                """);
            DatabaseSetup.apply(datasource, seed);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM lesson_category", Integer.class)).isEqualTo(24);
            assertThat(jdbc.queryForObject("SELECT name FROM lesson_category WHERE id='20000000-0000-0000-0000-000000000001'", String.class)).isEqualTo("Edited category");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_capability_route", Integer.class)).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_provider WHERE code='QWEN'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT encrypted_api_key FROM ai_provider WHERE code='DEEPSEEK' AND enabled", String.class)).isEqualTo("test-ciphertext");
            assertThat(jdbc.queryForObject("SELECT priority FROM ai_capability_route WHERE model='deepseek-flash' AND NOT enabled", Integer.class)).isEqualTo(17);
            assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .containsExactly("001", "002", "003");
        }
    }
}
