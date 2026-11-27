package com.lyreo.entitlement.infrastructure.persistence;

import com.lyreo.entitlement.application.port.EntitlementAuditRepository;
import com.lyreo.entitlement.domain.EntitlementAuditEntry;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class JdbcEntitlementAuditRepository implements EntitlementAuditRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcEntitlementAuditRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public void record(EntitlementAuditEntry entry) {
        jdbc.update(
            """
            INSERT INTO entitlement_audit_log (
                id, user_id, feature_key, operation_id, decision,
                policy_version, detail_json, actor, occurred_at
            ) VALUES (
                :id, :userId, :featureKey, :operationId, :decision,
                :policyVersion, CAST(:detailJson AS jsonb), :actor, :occurredAt
            )
            """,
            new MapSqlParameterSource()
                .addValue("id", entry.id())
                .addValue("userId", entry.userId(), Types.OTHER)
                .addValue("featureKey", entry.featureKey())
                .addValue("operationId", entry.operationId(), Types.VARCHAR)
                .addValue("decision", entry.decision())
                .addValue("policyVersion", entry.policyVersion())
                .addValue("detailJson", serialize(entry.detail()))
                .addValue("actor", entry.actor(), Types.VARCHAR)
                .addValue("occurredAt", Timestamp.from(entry.occurredAt()))
        );
    }

    @Override
    public List<EntitlementAuditEntry> findRecent(UUID userId, String featureKey, int limit) {
        int clampedLimit = Math.max(1, Math.min(limit, 200));
        return jdbc.query(
            """
            SELECT id, user_id, feature_key, operation_id, decision, policy_version, detail_json::text AS detail_json, actor, occurred_at
              FROM entitlement_audit_log
             WHERE (CAST(:userId AS uuid) IS NULL OR user_id = CAST(:userId AS uuid))
               AND (CAST(:featureKey AS varchar) IS NULL OR feature_key = CAST(:featureKey AS varchar))
             ORDER BY occurred_at DESC, id DESC
             LIMIT :limit
            """,
            new MapSqlParameterSource()
                .addValue("userId", userId, Types.OTHER)
                .addValue("featureKey", featureKey, Types.VARCHAR)
                .addValue("limit", clampedLimit),
            (rs, rowNum) -> new EntitlementAuditEntry(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("feature_key"),
                rs.getString("operation_id"),
                rs.getString("decision"),
                rs.getString("policy_version"),
                deserialize(rs.getString("detail_json")),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("actor")
            )
        );
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize entitlement audit detail", ex);
        }
    }

    private Map<String, Object> deserialize(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
