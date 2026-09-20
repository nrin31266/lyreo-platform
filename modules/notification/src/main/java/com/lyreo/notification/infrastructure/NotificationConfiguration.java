package com.lyreo.notification.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotificationConfiguration {
    @Bean
    InMemoryJobProgressFeed inMemoryJobProgressFeed() {
        return new InMemoryJobProgressFeed();
    }
}
