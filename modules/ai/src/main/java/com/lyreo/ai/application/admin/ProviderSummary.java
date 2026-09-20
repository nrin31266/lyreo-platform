package com.lyreo.ai.application.admin;

import java.util.UUID;

public record ProviderSummary(
    UUID id,
    String code,
    String displayName,
    String baseUrl,
    boolean enabled,
    String connectionStatus,
    String keyLast4,
    boolean configured
) {}
