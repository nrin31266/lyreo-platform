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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import org.springframework.modulith.NamedInterface;

/** Resolves provider/model routes and audits every concrete AI attempt. */
@NamedInterface(value = "application", propagate = false)
public final class AiInvocationService {
    private final ThreadLocal<java.util.function.Consumer<Attempt>> observer=new ThreadLocal<>();
    @NamedInterface(value = "application", propagate = false)
    public record Attempt(UUID invocationId,String provider,String model,String prompt,Map<String,Object> output,Integer inputTokens,Integer outputTokens,long latencyMs,String error,String capability,boolean fallback,Map<String,Object> diagnostics) {}
    @NamedInterface(value = "application", propagate = false)
    public interface Observation extends AutoCloseable { @Override void close(); }
    public Observation observe(java.util.function.Consumer<Attempt> consumer) {
        var previous=observer.get();observer.set(consumer);return ()->{if(previous==null) observer.remove();else observer.set(previous);};
    }
    private void observed(Attempt attempt) {var consumer=observer.get();if(consumer!=null) { try { consumer.accept(attempt); } catch(RuntimeException failure) { org.slf4j.LoggerFactory.getLogger(AiInvocationService.class).warn("Could not persist AI diagnostic for {}",attempt.invocationId()); } }}
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
        return executeForProvider(capability,null,businessPrompt,input,options);
    }

    /** Conservative envelope shared with callers that batch work across configured fallbacks. */
    public int requestTokenBudget(AiCapability capability) {
        return router.candidates(capability).stream().filter(this::permittedFreeRoute)
            .mapToInt(route -> route.providerCode().equalsIgnoreCase("GROQ") ? 7_500 : 16_000)
            .min().orElse(7_500);
    }

    public AiExecutionResult executeForProvider(AiCapability capability,String provider,String businessPrompt,
        Map<String,Object> input,Map<String,Object> options) {
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

        if(provider!=null) {
            candidates=candidates.stream().filter(route->provider.equalsIgnoreCase(route.providerCode())
                || "LOCAL_KOKORO".equals(provider) && "KOKORO".equalsIgnoreCase(route.providerCode())).toList();
            if(candidates.isEmpty()) throw new AiExecutionException("No enabled route for selected provider",false,"AI_ROUTE_NOT_CONFIGURED");
        }
        AiExecutionException last = null;
        int attemptedRoutes=0;
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
            boolean fallback=attemptedRoutes++>0;
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
                observed(new Attempt(invocationId,route.providerCode(),route.model(),businessPrompt,result.output(),result.inputTokens(),result.outputTokens(),result.latencyMs(),"",capability.name(),fallback,Map.of()));
                return result;
            } catch (AiExecutionException failure) {
                audit.failed(
                    invocationId,
                    Instant.now(),
                    Duration.between(started, Instant.now()).toMillis(),
                    failure.errorCode()
                );
                var evidence=failure.evidence();
                observed(new Attempt(invocationId,route.providerCode(),route.model(),businessPrompt,evidence==null?Map.of():evidence.output(),evidence==null?null:evidence.inputTokens(),evidence==null?null:evidence.outputTokens(),Duration.between(started,Instant.now()).toMillis(),failure.errorCode()+": "+failure.getMessage(),capability.name(),fallback,failure.diagnostics()));
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
                observed(new Attempt(invocationId,route.providerCode(),route.model(),businessPrompt,Map.of(),null,null,Duration.between(started,Instant.now()).toMillis(),failure.getClass().getSimpleName(),capability.name(),fallback,Map.of()));
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

    /** Explicit server maintenance only; callers reserve a durable budget before each call. */
    public AiExecutionResult executeMaintenance(String prompt,Map<String,Object> input,int outputBudget,String credential) {
        return executeMaintenance(prompt,input,outputBudget,credential,"DEEPSEEK","deepseek-flash");
    }

    public AiExecutionResult executeMaintenance(String prompt,Map<String,Object> input,int outputBudget,String credential,String provider,String model) {
        if(credential==null || credential.isBlank() || outputBudget<1 || outputBudget>8192)
            throw new IllegalArgumentException("Maintenance requires a server credential and bounded output");
        var options=new LinkedHashMap<String,Object>(Map.of("max_output_tokens",outputBudget,"response_schema","library_maintenance_v2"));
        switch(provider) {
            case "DEEPSEEK" -> { options.put("thinking","enabled"); options.put("provider_options",Map.of("reasoning_effort","high")); }
            case "GEMINI" -> { options.put("thinking_level","low"); options.put("include_reasoning_usage",true); }
            case "GROQ" -> options.put("provider_options",Map.of("reasoning_effort","medium"));
            default -> throw new IllegalArgumentException("Unsupported maintenance provider");
        }
        UUID id=UUID.randomUUID();Instant started=Instant.now();
        audit.started(id,AiCapability.GENERAL_LLM,provider,model,started,hashRequest(prompt,input));
        try {
            var result=gateway.execute(new AiExecutionCommand(id,AiCapability.GENERAL_LLM,provider,model,credential,prompt,input,options));
            audit.succeeded(id,Instant.now(),result.latencyMs(),result.inputTokens(),result.outputTokens());return result;
        } catch(RuntimeException failure) {
            audit.failed(id,Instant.now(),Duration.between(started,Instant.now()).toMillis(),failure instanceof AiExecutionException known?known.errorCode():"MAINTENANCE_FAILURE");
            throw failure;
        }
    }

    private boolean permittedFreeRoute(AiRoute route) {
        String provider = route.providerCode().toUpperCase(java.util.Locale.ROOT);
        String model = route.model();
        // DeepSeek is an explicitly configured paid route. Its credential, priority and spend
        // decision are owned by Admin, so it is allowed through the same normal route path.
        if (provider.equals("DEEPSEEK")) return model != null && model.startsWith("deepseek-");
        if (provider.equals("DEV_MOCK") && model.equals("mock")) return true;
        if (Set.of("LOCAL_KOKORO", "KOKORO").contains(provider)) {
            return route.capability() == AiCapability.TTS
                && Set.of("hexgrad/Kokoro-82M", "Kokoro-82M", "kokoro").contains(model);
        }
        if (provider.equals("LOCAL_NLP")) return route.capability() == AiCapability.NLP;
        if (provider.equals("LOCAL_WHISPERX")) {
            return route.capability() == AiCapability.STT && "small.en".equals(model)
                || route.capability() == AiCapability.ALIGNMENT && "WAV2VEC2_ASR_BASE_960H".equals(model);
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
