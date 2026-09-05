package com.lyreo.toeic.infrastructure;

import com.lyreo.toeic.application.ToeicAttemptRepository;
import com.lyreo.toeic.application.ToeicAttemptService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class ToeicConfiguration {

    @Bean
    ToeicAttemptRepository toeicAttemptRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcToeicAttemptRepository(jdbc);
    }

    @Bean
    ToeicAttemptService toeicAttemptService(
        ToeicAttemptRepository repository,
        ApplicationEventPublisher events
    ) {
        return new ToeicAttemptService(repository, events);
    }
}
