package com.lyreo.ai.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistence port for runtime provider/model routing configuration. */
public interface AiAdminRepository {
    List<Map<String, Object>> providers();
    List<Map<String, Object>> routes();

    UUID saveProvider(
        String code,
        String displayName,
        String baseUrl,
        String encryptedApiKey,
        String keyLast4,
        boolean enabled
    );

    UUID saveRoute(
        String capability,
        String providerCode,
        String model,
        int priority,
        boolean fallback,
        boolean enabled
    );
}
