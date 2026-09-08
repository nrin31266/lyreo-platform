package com.lyreo.grammar.infrastructure;

import com.lyreo.grammar.application.GrammarPracticeRepository;
import com.lyreo.grammar.application.GrammarPracticeScorer;
import com.lyreo.grammar.application.GrammarPracticeService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class GrammarConfiguration {
    @Bean
    GrammarPracticeRepository grammarPracticeRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcGrammarPracticeRepository(jdbc);
    }

    @Bean
    GrammarPracticeScorer grammarPracticeScorer() {
        return new GrammarPracticeScorer();
    }

    @Bean
    GrammarPracticeService grammarPracticeService(
        GrammarPracticeRepository repository,
        GrammarPracticeScorer scorer,
        ApplicationEventPublisher events
    ) {
        return new GrammarPracticeService(repository, scorer, events);
    }
}
