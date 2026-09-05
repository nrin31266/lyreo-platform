package com.lyreo.ai.infrastructure;

import com.lyreo.ai.application.AiInvocationAuditRepository;
import com.lyreo.ai.domain.AiCapability;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcAiInvocationAuditRepository implements AiInvocationAuditRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public JdbcAiInvocationAuditRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void started(UUID id, AiCapability capability, String provider, String model, Instant at, String requestHash) {
        jdbc.update("""
            INSERT INTO ai_invocation(id, capability, provider_code, model, status, started_at, request_hash)
            VALUES (:id, :capability, :provider, :model, 'RUNNING', :startedAt, :requestHash)
            """, new MapSqlParameterSource().addValue("id", id).addValue("capability", capability.name())
            .addValue("provider", provider).addValue("model", model).addValue("startedAt", at).addValue("requestHash", requestHash));
    }

    @Override
    public void succeeded(UUID id, Instant at, long latencyMs, Integer inputTokens, Integer outputTokens) {
        jdbc.update("""
            UPDATE ai_invocation SET status='SUCCEEDED', finished_at=:at, latency_ms=:latency,
              input_tokens=:inputTokens, output_tokens=:outputTokens WHERE id=:id
            """, new MapSqlParameterSource().addValue("id", id).addValue("at", at).addValue("latency", latencyMs)
            .addValue("inputTokens", inputTokens).addValue("outputTokens", outputTokens));
    }

    @Override
    public void failed(UUID id, Instant at, long latencyMs, String errorCode) {
        jdbc.update("""
            UPDATE ai_invocation SET status='FAILED', finished_at=:at, latency_ms=:latency, error_code=:error WHERE id=:id
            """, Map.of("id", id, "at", at, "latency", latencyMs, "error", errorCode));
    }
}
