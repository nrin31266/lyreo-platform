package com.lyreo.ai.application;

import com.lyreo.ai.domain.AiCapability;
import com.lyreo.ai.domain.AiRoute;
import java.util.List;

public final class AiRouter {
    private final AiRouteRepository repository;

    public AiRouter(AiRouteRepository repository) {
        this.repository = repository;
    }

    public List<AiRoute> candidates(AiCapability capability) {
        List<AiRoute> routes = repository.findEnabledRoutes(capability);
        if (routes.isEmpty()) {
            throw new IllegalStateException("No enabled AI route for " + capability);
        }
        return routes;
    }
}
