package com.lyreo.notification.infrastructure;

import com.lyreo.notification.application.JobProgressFeed;
import com.lyreo.notification.application.RealtimeNotificationPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotificationConfiguration {
    @Bean
    InMemoryJobProgressFeed inMemoryJobProgressFeed() {
        return new InMemoryJobProgressFeed();
    }

    @Bean
    JobProgressFeed jobProgressFeed(InMemoryJobProgressFeed feed) {
        return feed;
    }

    @Bean
    RealtimeNotificationPort realtimeNotificationPort(InMemoryJobProgressFeed feed) {
        return feed;
    }
}
