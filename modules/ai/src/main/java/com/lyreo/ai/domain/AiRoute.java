package com.lyreo.ai.domain;

import java.util.UUID;

public record AiRoute(
    UUID providerId,
    String providerCode,
    String model,
    AiCapability capability,
    int priority,
    boolean fallback
) {}
