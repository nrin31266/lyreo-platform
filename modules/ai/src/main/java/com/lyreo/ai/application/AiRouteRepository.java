package com.lyreo.ai.application;

import com.lyreo.ai.domain.AiCapability;
import com.lyreo.ai.domain.AiRoute;
import java.util.List;

public interface AiRouteRepository {
    List<AiRoute> findEnabledRoutes(AiCapability capability);
    String decryptedCredentialFor(AiRoute route);
}
