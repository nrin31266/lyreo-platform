package com.lyreo.entitlement.infrastructure;

import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.entitlement.application.internal.DefaultEntitlementService;
import com.lyreo.entitlement.application.internal.EntitlementGrantAdminService;
import com.lyreo.entitlement.application.port.EntitlementAuditRepository;
import com.lyreo.entitlement.application.port.FeatureRepository;
import com.lyreo.entitlement.application.port.LearnerPlanRepository;
import com.lyreo.entitlement.application.port.QuotaPolicyRepository;
import com.lyreo.entitlement.application.port.UsageQuotaRepository;
import com.lyreo.entitlement.application.port.UserEntitlementGrantRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EntitlementConfiguration {

    @Bean
    EntitlementService entitlementService(
        FeatureRepository featureRepository,
        LearnerPlanRepository plans,
        QuotaPolicyRepository quotaPolicyRepository,
        UsageQuotaRepository usageQuotaRepository,
        EntitlementAuditRepository auditRepository
    ) {
        return new DefaultEntitlementService(
            featureRepository,
            plans,
            quotaPolicyRepository,
            usageQuotaRepository,
            auditRepository,
            Clock.systemUTC()
        );
    }

    @Bean
    EntitlementGrantAdminService entitlementGrantAdminService(
        FeatureRepository featureRepository,
        UserEntitlementGrantRepository grantRepository,
        EntitlementAuditRepository auditRepository,
        QuotaPolicyRepository quotaPolicyRepository
    ) {
        return new EntitlementGrantAdminService(
            featureRepository,
            grantRepository,
            auditRepository,
            quotaPolicyRepository,
            Clock.systemUTC()
        );
    }
}
