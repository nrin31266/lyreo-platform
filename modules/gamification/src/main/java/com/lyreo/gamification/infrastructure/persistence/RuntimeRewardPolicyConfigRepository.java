package com.lyreo.gamification.infrastructure.persistence;

import com.lyreo.gamification.application.port.RewardPolicyConfigRepository;
import com.lyreo.gamification.domain.RewardPolicyConfig;
import com.lyreo.platform.config.application.RuntimeConfigService;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Reads gamification reward-policy config from module_runtime_config. */
public final class RuntimeRewardPolicyConfigRepository implements RewardPolicyConfigRepository {
    private static final String OWNER = "gamification";
    private static final String KEY = "reward-policy";

    private final RuntimeConfigService runtimeConfig;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public RuntimeRewardPolicyConfigRepository(RuntimeConfigService runtimeConfig) {
        this(runtimeConfig, new ObjectMapper());
    }

    public RuntimeRewardPolicyConfigRepository(RuntimeConfigService runtimeConfig, ObjectMapper mapper) {
        this.runtimeConfig = runtimeConfig;
        this.jdbc = null;
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    public RuntimeRewardPolicyConfigRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.runtimeConfig = null;
        this.jdbc = jdbc;
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    @Override
    public RewardPolicyConfig save(RewardPolicyConfig config) {
        if (runtimeConfig != null) {
            runtimeConfig.write(OWNER, KEY, 1, mapper.valueToTree(config), null);
            return current();
        }
        if (jdbc != null) {
            jdbc.update(
                """
                INSERT INTO module_runtime_config(owner_module, config_key, schema_version, config_json, updated_at)
                VALUES (:owner, :key, 1, CAST(:json AS jsonb), now())
                ON CONFLICT (owner_module, config_key) DO UPDATE SET
                    config_json = excluded.config_json,
                    updated_at = now()
                """,
                Map.of(
                    "owner", OWNER,
                    "key", KEY,
                    "json", mapper.writeValueAsString(config)
                )
            );
            return current();
        }
        return config;
    }

    @Override
    public RewardPolicyConfig current() {
        if (runtimeConfig != null) {
            return runtimeConfig.read(
                OWNER,
                KEY,
                RewardPolicyConfig.class,
                RewardPolicyConfig::defaults
            );
        }
        if (jdbc == null) {
            return RewardPolicyConfig.defaults();
        }
        return jdbc.query(
            """
            SELECT config_json::text AS config_json
            FROM module_runtime_config
            WHERE owner_module = :owner AND config_key = :key
            """,
            Map.of("owner", OWNER, "key", KEY),
            (rs, rowNum) -> rs.getString("config_json")
        ).stream().findFirst().map(this::parse).orElseGet(RewardPolicyConfig::defaults);
    }

    private RewardPolicyConfig parse(String json) {
        try {
            return mapper.readValue(json, RewardPolicyConfig.class);
        } catch (Exception ex) {
            return RewardPolicyConfig.defaults();
        }
    }
}
