package com.lyreo.platform.config.application;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.lyreo.platform.config.domain.RuntimeConfigDocument;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Technical config registry boundary.
 *
 * <p>Business modules should normally wrap this service behind their own typed policy repository
 * instead of reading arbitrary JSON throughout application/domain code.</p>
 */
public final class RuntimeConfigService {
    private final RuntimeConfigRepository repository;
    private final ObjectMapper mapper;

    public RuntimeConfigService(RuntimeConfigRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public <T> T read(
        String ownerModule,
        String configKey,
        Class<T> type,
        Supplier<T> defaultValue
    ) {
        return repository.find(ownerModule, configKey)
            .map(RuntimeConfigDocument::config)
            .map(node -> convert(node, type))
            .orElseGet(defaultValue);
    }

    public RuntimeConfigDocument write(
        String ownerModule,
        String configKey,
        int schemaVersion,
        JsonNode config,
        UUID updatedBy
    ) {
        if (ownerModule == null || ownerModule.isBlank()) throw new IllegalArgumentException("ownerModule is required");
        if (configKey == null || configKey.isBlank()) throw new IllegalArgumentException("configKey is required");
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be >= 1");
        if (config == null || !config.isObject()) throw new IllegalArgumentException("config must be a JSON object");

        return repository.save(new RuntimeConfigDocument(
            ownerModule.trim(),
            configKey.trim(),
            schemaVersion,
            config.deepCopy(),
            updatedBy,
            Instant.now()
        ));
    }

    private <T> T convert(JsonNode node, Class<T> type) {
        try {
            return mapper.treeToValue(node, type);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid runtime config for type " + type.getSimpleName(), e);
        }
    }
}
