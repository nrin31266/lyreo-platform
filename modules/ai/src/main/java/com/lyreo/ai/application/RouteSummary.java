package com.lyreo.ai.application;

import java.util.UUID;

public record RouteSummary(
    UUID id,
    String capability,
    String provider,
    String model,
    int priority,
    boolean fallback,
    boolean enabled
) {}
