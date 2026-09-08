package com.lyreo.lexicon.infrastructure;

import com.lyreo.lexicon.application.LexiconRepository;
import com.lyreo.lexicon.application.LexiconSearchService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class LexiconConfiguration {
    @Bean
    LexiconRepository lexiconRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcLexiconRepository(jdbc);
    }

    @Bean
    LexiconSearchService lexiconSearchService(LexiconRepository repository) {
        return new LexiconSearchService(repository);
    }
}
