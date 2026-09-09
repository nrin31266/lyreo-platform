package com.lyreo.ai.infrastructure;

import com.lyreo.ai.application.AiAdminRepository;
import com.lyreo.contracts.errors.ResourceNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcAiAdminRepository implements AiAdminRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAiAdminRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Map<String, Object>> providers() {
        return jdbc.queryForList(
            """
            SELECT id, code, display_name, base_url, enabled, connection_status,
                   key_last4, (encrypted_api_key IS NOT NULL) configured
              FROM ai_provider
             ORDER BY code
            """,
            Map.of()
        );
    }

    @Override
    public List<Map<String, Object>> routes() {
        return jdbc.queryForList(
            """
            SELECT r.id, r.capability, p.code provider, r.model, r.priority,
                   r.is_fallback, r.enabled, r.config_json
              FROM ai_capability_route r
              JOIN ai_provider p ON p.id=r.provider_id
             ORDER BY r.capability, r.priority
            """,
            Map.of()
        );
    }

    @Override
    public UUID saveProvider(
        String code,
        String displayName,
        String baseUrl,
        String encryptedApiKey,
        String keyLast4,
        boolean enabled
    ) {
        UUID existing = jdbc.query(
            "SELECT id FROM ai_provider WHERE code=:code",
            Map.of("code", code),
            rs -> rs.next() ? rs.getObject(1, UUID.class) : null
        );
        UUID id = existing == null ? UUID.randomUUID() : existing;
        jdbc.update(
            """
            INSERT INTO ai_provider(
              id,code,display_name,base_url,encrypted_api_key,key_last4,enabled,created_at,updated_at
            ) VALUES(:id,:code,:name,:baseUrl,:encrypted,:last4,:enabled,now(),now())
            ON CONFLICT(code) DO UPDATE SET
              display_name=excluded.display_name,
              base_url=excluded.base_url,
              enabled=excluded.enabled,
              updated_at=now(),
              encrypted_api_key=COALESCE(excluded.encrypted_api_key,ai_provider.encrypted_api_key),
              key_last4=COALESCE(excluded.key_last4,ai_provider.key_last4)
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("code", code)
                .addValue("name", displayName)
                .addValue("baseUrl", baseUrl)
                .addValue("enabled", enabled)
                .addValue("encrypted", encryptedApiKey)
                .addValue("last4", keyLast4)
        );
        return id;
    }

    @Override
    public UUID saveRoute(
        String capability,
        String providerCode,
        String model,
        int priority,
        boolean fallback,
        boolean enabled
    ) {
        List<UUID> providers = jdbc.query(
            "SELECT id FROM ai_provider WHERE code=:code",
            Map.of("code", providerCode),
            (rs, rowNum) -> rs.getObject("id", UUID.class)
        );
        if (providers.isEmpty()) {
            throw new ResourceNotFoundException("Unknown AI provider: " + providerCode);
        }
        UUID provider = providers.get(0);
        UUID id = UUID.randomUUID();
        jdbc.update(
            """
            INSERT INTO ai_capability_route(id,capability,provider_id,model,priority,is_fallback,enabled)
            VALUES(:id,:capability,:provider,:model,:priority,:fallback,:enabled)
            ON CONFLICT(capability,provider_id,model) DO UPDATE SET
              priority=excluded.priority,
              is_fallback=excluded.is_fallback,
              enabled=excluded.enabled
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("capability", capability)
                .addValue("provider", provider)
                .addValue("model", model)
                .addValue("priority", priority)
                .addValue("fallback", fallback)
                .addValue("enabled", enabled)
        );
        UUID resolved = jdbc.queryForObject(
            """
            SELECT id FROM ai_capability_route
             WHERE capability=:capability AND provider_id=:provider AND model=:model
            """,
            Map.of("capability", capability, "provider", provider, "model", model),
            UUID.class
        );
        return resolved == null ? id : resolved;
    }
}
