package com.lyreo.ai.application;

import com.lyreo.ai.domain.AiCapability;
import com.lyreo.ai.domain.AiRoute;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces a credential-free routing snapshot for durable business jobs.
 *
 * <p>The snapshot is diagnostic/audit metadata: it records which routes were enabled when
 * the job was accepted. Execution still resolves live routes so an operator can disable a
 * broken provider or change a fallback without rewriting queued jobs. If Lyreo later needs
 * fully pinned reproducibility, introduce that explicitly rather than silently changing this
 * semantic.</p>
 */
public final class AiRoutingSnapshotService {
    private final AiRouteRepository routes;

    public AiRoutingSnapshotService(AiRouteRepository routes) {
        this.routes = routes;
    }

    public Map<String, List<RouteSnapshot>> snapshot(Collection<AiCapability> capabilities) {
        Map<String, List<RouteSnapshot>> snapshot = new LinkedHashMap<>();
        capabilities.stream().distinct().forEach(capability -> {
            List<RouteSnapshot> candidates = routes.findEnabledRoutes(capability).stream()
                .map(AiRoutingSnapshotService::toSnapshot)
                .toList();
            snapshot.put(capability.name(), candidates);
        });
        return Map.copyOf(snapshot);
    }

    private static RouteSnapshot toSnapshot(AiRoute route) {
        return new RouteSnapshot(
            route.providerCode(),
            route.model(),
            route.priority(),
            route.fallback()
        );
    }

    public record RouteSnapshot(
        String provider,
        String model,
        int priority,
        boolean fallback
    ) {}
}
