package com.lyreo.ai.application.execution;

import com.lyreo.ai.application.port.AiExecutionGateway;
import com.lyreo.ai.application.port.AiInvocationAuditRepository;
import com.lyreo.ai.application.port.AiRouteRepository;
import com.lyreo.ai.application.routing.AiRouter;
import com.lyreo.ai.domain.AiCapability;
import com.lyreo.ai.domain.AiRoute;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import org.springframework.modulith.NamedInterface;

/** Resolves provider/model routes and audits every concrete AI attempt. */
@NamedInterface(value = "application", propagate = false)
public final class AiInvocationService {
    private final AiRouter router;
    private final AiRouteRepository routes;
    private final AiExecutionGateway gateway;
    private final AiInvocationAuditRepository audit;
    private final Set<String> confirmedFreeProviders;

    public AiInvocationService(
        AiRouter router,
        AiRouteRepository routes,
        AiExecutionGateway gateway,
        AiInvocationAuditRepository audit
    ) {
        this(router, routes, gateway, audit, Set.of());
    }

    public AiInvocationService(AiRouter router, AiRouteRepository routes, AiExecutionGateway gateway,
        AiInvocationAuditRepository audit, Set<String> confirmedFreeProviders) {
        this.router = router;
        this.routes = routes;
        this.gateway = gateway;
        this.audit = audit;
        this.confirmedFreeProviders = Set.copyOf(confirmedFreeProviders);
    }

    /**
     * Provider fallback is runtime policy. Business prompts remain owned by the calling module.
     * Every concrete attempt gets its own audit row; secrets and full prompts are never persisted
     * in that operational audit table.
     */
    public AiExecutionResult execute(
        AiCapability capability,
        String businessPrompt,
        Map<String, Object> input,
        Map<String, Object> options
    ) {
        List<AiRoute> candidates;
        try {
            candidates = router.candidates(capability);
        } catch (RuntimeException noRoute) {
            throw new AiExecutionException(
                "No enabled AI route for " + capability,
                false,
                "AI_ROUTE_NOT_CONFIGURED",
                noRoute
            );
        }

        AiExecutionException last = null;
        for (AiRoute route : candidates) {
            if (!permittedFreeRoute(route)) {
                if (last == null) last = new AiExecutionException("Route is not approved for free-only execution", false,
                    "AI_FREE_ONLY_ROUTE_BLOCKED");
                continue;
            }
            if (route.providerCode().equalsIgnoreCase("GROQ")
                && (capability == AiCapability.GENERAL_LLM || capability == AiCapability.REASONING_LLM)
                && groqTokenUpperBound(businessPrompt, input, options) > 7_500) {
                if (last == null) last = new AiExecutionException("Input and output budget exceed the free Groq request budget",
                    false, "AI_FREE_TOKEN_BUDGET_EXCEEDED");
                continue;
            }
            UUID invocationId = UUID.randomUUID();
            Instant started = Instant.now();
            audit.started(
                invocationId,
                capability,
                route.providerCode(),
                route.model(),
                started,
                hashRequest(businessPrompt, input)
            );

            try {
                AiExecutionResult result = gateway.execute(new AiExecutionCommand(
                    invocationId,
                    capability,
                    route.providerCode(),
                    route.model(),
                    routes.decryptedCredentialFor(route),
                    businessPrompt,
                    input == null ? Map.of() : input,
                    options == null ? Map.of() : options
                ));
                audit.succeeded(
                    invocationId,
                    Instant.now(),
                    result.latencyMs(),
                    result.inputTokens(),
                    result.outputTokens()
                );
                return result;
            } catch (AiExecutionException failure) {
                audit.failed(
                    invocationId,
                    Instant.now(),
                    Duration.between(started, Instant.now()).toMillis(),
                    failure.errorCode()
                );
                last = failure;
                if (!failure.fallbackAllowed()) {
                    throw failure;
                }
            } catch (RuntimeException failure) {
                audit.failed(
                    invocationId,
                    Instant.now(),
                    Duration.between(started, Instant.now()).toMillis(),
                    failure.getClass().getSimpleName()
                );
                last = new AiExecutionException(
                    "Unexpected AI route failure",
                    true,
                    "AI_ROUTE_FAILURE",
                    failure
                );
            }
        }

        if (last != null && (!last.fallbackAllowed() || last.retryAfterSeconds() > 0)) throw last;
        throw new AiExecutionException(
            "All enabled AI routes failed for " + capability,
            true,
            last == null ? "AI_ALL_ROUTES_FAILED" : last.errorCode(),
            last
        );
    }

    private boolean permittedFreeRoute(AiRoute route) {
        String provider = route.providerCode().toUpperCase(java.util.Locale.ROOT);
        String model = route.model();
        if (provider.equals("DEV_MOCK") && model.equals("mock")) return true;
        if (Set.of("LOCAL_KOKORO", "KOKORO").contains(provider)) {
            return route.capability() == AiCapability.TTS
                && Set.of("hexgrad/Kokoro-82M", "Kokoro-82M", "kokoro").contains(model);
        }
        if (provider.equals("LOCAL_NLP")) return route.capability() == AiCapability.NLP;
        if (Set.of("QWEN", "QWEN3", "QWEN3_ASR", "QWEN3_FORCED_ALIGNER", "LOCAL_QWEN").contains(provider)) {
            return model.equals("Qwen/Qwen3-ASR-0.6B") || model.equals("Qwen/Qwen3-ForcedAligner-0.6B");
        }
        if (!confirmedFreeProviders.contains(provider)) return false;
        return (provider.equals("GEMINI") && model.equals("gemini-3.5-flash-lite"))
            || (provider.equals("GROQ") && Set.of("whisper-large-v3-turbo", "openai/gpt-oss-120b").contains(model));
    }

    private static long groqTokenUpperBound(String prompt, Map<String, Object> input, Map<String, Object> options) {
        // A UTF-8 byte bound is deliberately conservative; never send an oversized fallback blindly.
        long bytes = String.valueOf(prompt).getBytes(StandardCharsets.UTF_8).length
            + String.valueOf(input).getBytes(StandardCharsets.UTF_8).length
            + String.valueOf(options == null ? null : options.get("json_schema")).getBytes(StandardCharsets.UTF_8).length;
        Object configured = options == null ? null : options.get("max_output_tokens");
        if (!(configured instanceof Number) && options != null && options.get("provider_options") instanceof Map<?, ?> provider) {
            configured = provider.get("max_completion_tokens");
        }
        long output = configured instanceof Number n ? Math.max(1, n.longValue()) : 2_048;
        return bytes + output + 512;
    }

    private static String hashRequest(String prompt, Map<String, Object> input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((prompt == null ? "" : prompt).getBytes(StandardCharsets.UTF_8));
            digest.update(String.valueOf(input == null ? Map.of() : input).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
