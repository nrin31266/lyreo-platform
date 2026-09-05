package com.lyreo.ai.infrastructure;

import com.lyreo.ai.application.AiRouteRepository;
import com.lyreo.ai.domain.AiCapability;
import com.lyreo.ai.domain.AiRoute;
import com.lyreo.platform.security.SecretCipher;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcAiRouteRepository implements AiRouteRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final SecretCipher cipher;

    public JdbcAiRouteRepository(NamedParameterJdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public List<AiRoute> findEnabledRoutes(AiCapability capability) {
        return jdbc.query("""
            SELECT p.id provider_id, p.code provider_code, r.model,
                   r.capability, r.priority, r.is_fallback
              FROM ai_capability_route r
              JOIN ai_provider p ON p.id = r.provider_id
             WHERE r.capability = :capability
               AND r.enabled = TRUE
               AND p.enabled = TRUE
             ORDER BY r.priority
            """, Map.of("capability", capability.name()), (rs, rowNum) -> new AiRoute(
                rs.getObject("provider_id", UUID.class),
                rs.getString("provider_code"),
                rs.getString("model"),
                AiCapability.valueOf(rs.getString("capability")),
                rs.getInt("priority"),
                rs.getBoolean("is_fallback")
            ));
    }

    @Override
    public String decryptedCredentialFor(AiRoute route) {
        return jdbc.query("""
            SELECT encrypted_api_key
              FROM ai_provider
             WHERE id = :id
            """, Map.of("id", route.providerId()), rs -> {
                if (!rs.next()) return null;
                String encrypted = rs.getString(1);
                return encrypted == null ? null : cipher.decrypt(encrypted);
            });
    }
}
