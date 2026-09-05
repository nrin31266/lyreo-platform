package com.lyreo.platform.config.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyreo.platform.config.application.RuntimeConfigRepository;
import com.lyreo.platform.config.domain.RuntimeConfigDocument;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class JdbcRuntimeConfigRepository implements RuntimeConfigRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcRuntimeConfigRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Optional<RuntimeConfigDocument> find(String ownerModule, String configKey) {
        return jdbc.query("""
            SELECT owner_module, config_key, schema_version, config_json::text, updated_by, updated_at
              FROM module_runtime_config
             WHERE owner_module=:owner AND config_key=:key
            """, Map.of("owner", ownerModule, "key", configKey), (rs, rowNum) -> {
            try {
                return new RuntimeConfigDocument(
                    rs.getString("owner_module"),
                    rs.getString("config_key"),
                    rs.getInt("schema_version"),
                    mapper.readTree(rs.getString("config_json")),
                    rs.getObject("updated_by", java.util.UUID.class),
                    rs.getTimestamp("updated_at").toInstant()
                );
            } catch (Exception e) {
                throw new IllegalStateException("Unable to deserialize module runtime config", e);
            }
        }).stream().findFirst();
    }

    @Override
    public RuntimeConfigDocument save(RuntimeConfigDocument document) {
        try {
            jdbc.update("""
                INSERT INTO module_runtime_config(owner_module,config_key,schema_version,config_json,updated_by,updated_at)
                VALUES(:owner,:key,:version,CAST(:config AS jsonb),:updatedBy,:updatedAt)
                ON CONFLICT(owner_module,config_key) DO UPDATE SET
                  schema_version=excluded.schema_version,
                  config_json=excluded.config_json,
                  updated_by=excluded.updated_by,
                  updated_at=excluded.updated_at
                """, new MapSqlParameterSource()
                .addValue("owner", document.ownerModule())
                .addValue("key", document.configKey())
                .addValue("version", document.schemaVersion())
                .addValue("config", mapper.writeValueAsString(document.config()))
                .addValue("updatedBy", document.updatedBy())
                .addValue("updatedAt", document.updatedAt()));
            return document;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to persist module runtime config", e);
        }
    }
}
