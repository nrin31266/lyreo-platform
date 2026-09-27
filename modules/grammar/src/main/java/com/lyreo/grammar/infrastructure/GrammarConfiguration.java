package com.lyreo.grammar.infrastructure;

import com.lyreo.grammar.application.GrammarPracticeScorer;
import com.lyreo.grammar.application.GrammarPracticeService;
import com.lyreo.grammar.application.port.GrammarPracticeRepository;
import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.grammar.infrastructure.persistence.JdbcGrammarPracticeRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class GrammarConfiguration {
    @Bean
    GrammarPracticeRepository grammarPracticeRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        return new JdbcGrammarPracticeRepository(jdbc, objectMapper);
    }

    @Bean
    GrammarPracticeScorer grammarPracticeScorer() {
        return new GrammarPracticeScorer();
    }

    @Bean
    GrammarPracticeService grammarPracticeService(
        GrammarPracticeRepository repository,
        GrammarPracticeScorer scorer,
        ApplicationEventPublisher events,
        EntitlementService entitlements
    ) {
        return new GrammarPracticeService(repository, scorer, events, entitlements);
    }
}
