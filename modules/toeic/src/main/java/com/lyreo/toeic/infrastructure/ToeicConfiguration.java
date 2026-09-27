package com.lyreo.toeic.infrastructure;

import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.toeic.application.ToeicAttemptService;
import com.lyreo.toeic.application.ToeicTestQueryService;
import com.lyreo.toeic.application.port.ToeicAttemptRepository;
import com.lyreo.toeic.application.port.ToeicTestContentRepository;
import com.lyreo.toeic.infrastructure.persistence.JdbcToeicAttemptRepository;
import com.lyreo.toeic.infrastructure.persistence.JdbcToeicTestContentRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class ToeicConfiguration {

    @Bean
    ToeicAttemptRepository toeicAttemptRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcToeicAttemptRepository(jdbc);
    }

    @Bean
    ToeicTestContentRepository toeicTestContentRepository(
        NamedParameterJdbcTemplate jdbc,
        ObjectMapper objectMapper
    ) {
        return new JdbcToeicTestContentRepository(jdbc, objectMapper);
    }

    @Bean
    ToeicAttemptService toeicAttemptService(
        ToeicAttemptRepository repository,
        ApplicationEventPublisher events,
        EntitlementService entitlements
    ) {
        return new ToeicAttemptService(repository, events, entitlements);
    }

    @Bean
    ToeicTestQueryService toeicTestQueryService(
        ToeicAttemptRepository attempts,
        ToeicTestContentRepository content,
        EntitlementService entitlements
    ) {
        return new ToeicTestQueryService(attempts, content, entitlements);
    }
}
