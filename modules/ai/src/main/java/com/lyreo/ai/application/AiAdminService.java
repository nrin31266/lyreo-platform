package com.lyreo.ai.application;

import com.lyreo.platform.security.SecretCipher;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AiAdminService {
    private final AiAdminRepository repository;
    private final SecretCipher cipher;

    public AiAdminService(AiAdminRepository repository, SecretCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    public List<Map<String, Object>> providers() {
        return repository.providers();
    }

    public List<Map<String, Object>> routes() {
        return repository.routes();
    }

    public UUID saveProvider(
        String code,
        String displayName,
        String baseUrl,
        String apiKey,
        boolean enabled
    ) {
        String encrypted = apiKey == null || apiKey.isBlank() ? null : cipher.encrypt(apiKey);
        String last4 = apiKey == null || apiKey.isBlank()
            ? null
            : apiKey.substring(Math.max(0, apiKey.length() - 4));
        return repository.saveProvider(
            code, displayName, baseUrl, encrypted, last4, enabled
        );
    }

    public UUID saveRoute(
        String capability,
        String providerCode,
        String model,
        int priority,
        boolean fallback,
        boolean enabled
    ) {
        return repository.saveRoute(
            capability, providerCode, model, priority, fallback, enabled
        );
    }
}
