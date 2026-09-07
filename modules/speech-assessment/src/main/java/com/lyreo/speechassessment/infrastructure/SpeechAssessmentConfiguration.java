package com.lyreo.speechassessment.infrastructure;

import tools.jackson.databind.ObjectMapper;
import com.lyreo.ai.application.AiInvocationService;
import com.lyreo.platform.storage.ObjectStoragePort;
import com.lyreo.speechassessment.application.SpeechAssessmentRepository;
import com.lyreo.speechassessment.application.SpeechAssessmentService;
import com.lyreo.speechassessment.application.SpeechScoringPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class SpeechAssessmentConfiguration {
    @Bean
    SpeechAssessmentRepository speechAssessmentRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcSpeechAssessmentRepository(jdbc);
    }

    @Bean
    SpeechScoringPolicy speechScoringPolicy() {
        return new SpeechScoringPolicy();
    }

    @Bean
    SpeechAssessmentService speechAssessmentService(
        AiInvocationService ai,
        ObjectStoragePort storage,
        SpeechAssessmentRepository repository,
        SpeechScoringPolicy scoring,
        ObjectMapper mapper
    ) {
        return new SpeechAssessmentService(ai, storage, repository, scoring, mapper);
    }
}
