package com.lyreo.learner.infrastructure;

import tools.jackson.databind.ObjectMapper;
import com.lyreo.learner.application.LearnerProfileRepository;
import com.lyreo.learner.application.LearnerProfileService;
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
    LearnerProfileService learnerProfileService(LearnerProfileRepository repository) {
        return new LearnerProfileService(repository);
    }

    @Bean
    OnboardingService onboardingService(LearnerProfileRepository repository) {
        return new OnboardingService(repository);
    }
}
