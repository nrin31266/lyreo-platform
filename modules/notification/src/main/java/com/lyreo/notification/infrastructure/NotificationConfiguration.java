package com.lyreo.notification.infrastructure;

import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.notification.application.LearnerNotificationService;
import com.lyreo.notification.application.port.LearnerNotificationRepository;
import com.lyreo.notification.infrastructure.persistence.JdbcLearnerNotificationRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class NotificationConfiguration {
    @Bean
    InMemoryJobProgressFeed inMemoryJobProgressFeed() {
        return new InMemoryJobProgressFeed();
    }

    @Bean
    LearnerNotificationRepository learnerNotificationRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcLearnerNotificationRepository(jdbc);
    }

    @Bean
    LearnerNotificationService learnerNotificationService(
        LearnerNotificationRepository repository,
        ObjectProvider<EntitlementService> entitlementProvider
    ) {
        return new LearnerNotificationService(repository, entitlementProvider.getIfAvailable());
    }
}
