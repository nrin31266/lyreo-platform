package com.lyreo.ai.api;

import com.lyreo.ai.application.AiAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
    public ProviderResponse provider(
        @PathVariable String code,
        @Valid @RequestBody ProviderRequest request
    ) {
        String normalizedCode = code.toUpperCase();
        UUID id = service.saveProvider(
            normalizedCode,
            request.displayName(),
            request.baseUrl(),
            request.apiKey(),
            request.enabled()
        );
        return new ProviderResponse(
            id,
            normalizedCode,
            request.apiKey() != null && !request.apiKey().isBlank()
        );
    }

    @PostMapping("/routes")
    public RouteResponse route(@Valid @RequestBody RouteRequest request) {
        UUID id = service.saveRoute(
            request.capability(),
            request.providerCode().toUpperCase(),
            request.model(),
            request.priority(),
            request.fallback(),
            request.enabled()
        );
        return new RouteResponse(id);
    }

    public record ProviderRequest(
        @NotBlank(message = "displayName is required")
        String displayName,
        String baseUrl,
        String apiKey,
        boolean enabled
    ) {}

    public record RouteRequest(
        @NotBlank(message = "capability is required")
        String capability,
        @NotBlank(message = "providerCode is required")
        String providerCode,
        @NotBlank(message = "model is required")
        String model,
        int priority,
        boolean fallback,
        boolean enabled
    ) {}

    public record ProviderResponse(
        UUID id,
        String code,
        boolean credentialAccepted
    ) {}

    public record RouteResponse(
        UUID id
    ) {}
}
