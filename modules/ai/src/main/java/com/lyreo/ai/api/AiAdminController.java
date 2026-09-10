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
    public List<ProviderSummaryResponse> providers() {
        return service.providers().stream()
            .map(p -> new ProviderSummaryResponse(
                p.id(),
                p.code(),
                p.displayName(),
                p.baseUrl(),
                p.enabled(),
                p.connectionStatus(),
                p.keyLast4(),
                p.configured()
            ))
            .toList();
    }

    @GetMapping("/routes")
    public List<RouteSummaryResponse> routes() {
        return service.routes().stream()
            .map(r -> new RouteSummaryResponse(
                r.id(),
                r.capability(),
                r.provider(),
                r.model(),
                r.priority(),
                r.fallback(),
                r.enabled()
            ))
            .toList();
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

    public record ProviderSummaryResponse(
        UUID id,
        String code,
        String display_name,
        String base_url,
        boolean enabled,
        String connection_status,
        String key_last4,
        boolean configured
    ) {}

    public record RouteSummaryResponse(
        UUID id,
        String capability,
        String provider,
        String model,
        int priority,
        boolean is_fallback,
        boolean enabled
    ) {}
}
