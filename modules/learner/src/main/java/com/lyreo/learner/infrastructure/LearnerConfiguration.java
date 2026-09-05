package com.lyreo.learner.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyreo.learner.application.LearnerProfileRepository;
import com.lyreo.learner.application.OnboardingService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class LearnerConfiguration {
    @Bean
    LearnerProfileRepository learnerProfileRepository(
        NamedParameterJdbcTemplate jdbc,
        ObjectMapper mapper
    ) {
        return new JdbcLearnerProfileRepository(jdbc, mapper);
    }

    @Bean
    OnboardingService onboardingService(LearnerProfileRepository repository) {
        return new OnboardingService(repository);
    }
}
