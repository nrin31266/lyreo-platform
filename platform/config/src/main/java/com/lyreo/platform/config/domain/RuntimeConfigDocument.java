package com.lyreo.platform.config.domain;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * Versioned runtime configuration document owned logically by one module.
 *
 * <p>This is not a generic bag for domain invariants. It is intended for admin-tunable
 * policies/toggles whose schema is still represented by a typed object in the owning module.</p>
 */
public record RuntimeConfigDocument(
    String ownerModule,
    String configKey,
    int schemaVersion,
    JsonNode config,
    UUID updatedBy,
    Instant updatedAt
) {}
