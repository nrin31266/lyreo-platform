package com.lyreo.ai.api;

import com.lyreo.ai.application.AiAdminService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/ai")
@PreAuthorize("hasRole('ADMIN')")
public class AiAdminController {
    private final AiAdminService service;

    public AiAdminController(AiAdminService service) {
        this.service = service;
    }

    @GetMapping("/providers")
    public List<Map<String, Object>> providers() {
        return service.providers();
    }

    @GetMapping("/routes")
    public List<Map<String, Object>> routes() {
        return service.routes();
    }

    @PutMapping("/providers/{code}")
    public Map<String, Object> provider(
        @PathVariable String code,
        @RequestBody ProviderRequest request
    ) {
        String normalizedCode = code.toUpperCase();
        UUID id = service.saveProvider(
            normalizedCode,
            request.displayName(),
            request.baseUrl(),
            request.apiKey(),
            request.enabled()
        );
        return Map.of(
            "id", id,
            "code", normalizedCode,
            "credentialAccepted", request.apiKey() != null && !request.apiKey().isBlank()
        );
    }

    @PostMapping("/routes")
    public Map<String, Object> route(@RequestBody RouteRequest request) {
        return Map.of(
            "id",
            service.saveRoute(
                request.capability(),
                request.providerCode().toUpperCase(),
                request.model(),
                request.priority(),
                request.fallback(),
                request.enabled()
            )
        );
    }

    public record ProviderRequest(
        String displayName,
        String baseUrl,
        String apiKey,
        boolean enabled
    ) {}

    public record RouteRequest(
        String capability,
        String providerCode,
        String model,
        int priority,
        boolean fallback,
        boolean enabled
    ) {}
}
