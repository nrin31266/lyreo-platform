package com.lyreo.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.lyreo.ai.domain.AiCapability;
import com.lyreo.ai.infrastructure.persistence.JdbcAiInvocationAuditRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(classes = AiInvocationAuditIT.Config.class)
class AiInvocationAuditIT {
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine");
    static { postgres.start(); }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Config {}

    @Autowired JdbcTemplate jdbc;

    @Test void recordsSuccessAndFailureWithActualPostgresTimestampBindings() {
        var audit = new JdbcAiInvocationAuditRepository(new NamedParameterJdbcTemplate(jdbc));
        Instant start = Instant.parse("2026-10-26T00:00:00.123456Z");
        UUID success = UUID.randomUUID();
        audit.started(success, AiCapability.STT, "GROQ", "whisper-large-v3-turbo", start, "hash");
        assertThat(jdbc.queryForObject("SELECT started_at FROM ai_invocation WHERE id=?",
            java.sql.Timestamp.class, success).toInstant()).isEqualTo(start);
        audit.succeeded(success, start.plusSeconds(1), 1000, null, null);
        assertThat(jdbc.queryForMap("SELECT status, latency_ms, input_tokens, output_tokens FROM ai_invocation WHERE id=?", success))
            .containsEntry("status", "SUCCEEDED").containsEntry("latency_ms", 1000L)
            .containsEntry("input_tokens", null).containsEntry("output_tokens", null);
        assertThat(jdbc.queryForObject("SELECT finished_at FROM ai_invocation WHERE id=?",
            java.sql.Timestamp.class, success).toInstant()).isEqualTo(start.plusSeconds(1));

        UUID failure = UUID.randomUUID();
        audit.started(failure, AiCapability.GENERAL_LLM, "GEMINI", "gemini-3.5-flash-lite", start, "hash");
        audit.failed(failure, start.plusSeconds(2), 2000, "PROVIDER_QUOTA_WAIT");
        assertThat(jdbc.queryForMap("SELECT status, error_code FROM ai_invocation WHERE id=?", failure))
            .containsEntry("status", "FAILED").containsEntry("error_code", "PROVIDER_QUOTA_WAIT");
        assertThat(jdbc.queryForObject("SELECT finished_at FROM ai_invocation WHERE id=?",
            java.sql.Timestamp.class, failure).toInstant()).isEqualTo(start.plusSeconds(2));
    }
}
