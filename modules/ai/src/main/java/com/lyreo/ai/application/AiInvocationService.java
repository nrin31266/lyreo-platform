package com.lyreo.ai.application;

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
import org.springframework.modulith.NamedInterface;

/** Resolves provider/model routes and audits every concrete AI attempt. */
@NamedInterface("application")
public final class AiInvocationService {
    private final AiRouter router;
    private final AiRouteRepository routes;
    private final AiExecutionGateway gateway;
    private final AiInvocationAuditRepository audit;

    public AiInvocationService(
        AiRouter router,
        AiRouteRepository routes,
        AiExecutionGateway gateway,
        AiInvocationAuditRepository audit
    ) {
        this.router = router;
        this.routes = routes;
        this.gateway = gateway;
        this.audit = audit;
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

        throw new AiExecutionException(
            "All enabled AI routes failed for " + capability,
            true,
            last == null ? "AI_ALL_ROUTES_FAILED" : last.errorCode(),
            last
        );
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
