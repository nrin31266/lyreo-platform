package com.lyreo.entitlement.infrastructure;

import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.entitlement.application.internal.DefaultEntitlementService;
import com.lyreo.entitlement.application.port.FeatureRepository;
import com.lyreo.entitlement.application.port.UserEntitlementGrantRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class EntitlementConfiguration {

    @Bean
    EntitlementService entitlementService(
        FeatureRepository featureRepository,
        UserEntitlementGrantRepository grantRepository
    ) {
        return new DefaultEntitlementService(featureRepository, grantRepository, Clock.systemUTC());
    }
}
