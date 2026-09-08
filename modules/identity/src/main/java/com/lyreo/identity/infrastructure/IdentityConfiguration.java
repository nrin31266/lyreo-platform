package com.lyreo.identity.infrastructure;

import com.lyreo.identity.application.AppUserProvisioningService;
import com.lyreo.identity.application.AppUserRepository;
import com.lyreo.identity.infrastructure.persistence.SpringDataAppUserJpaRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IdentityConfiguration {

    @Bean
    AppUserRepository appUserRepository(SpringDataAppUserJpaRepository repository) {
        return new JpaAppUserRepositoryAdapter(repository);
    }

    @Bean
    AppUserProvisioningService appUserProvisioningService(AppUserRepository repository) {
        return new AppUserProvisioningService(repository);
    }
}
