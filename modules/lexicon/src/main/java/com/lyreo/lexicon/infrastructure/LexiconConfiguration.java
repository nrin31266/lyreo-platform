package com.lyreo.lexicon.infrastructure;

import com.lyreo.lexicon.application.LexiconSearchService;
import com.lyreo.lexicon.application.port.LexiconRepository;
import com.lyreo.lexicon.infrastructure.persistence.JdbcLexiconRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class LexiconConfiguration {
    @Bean
    com.lyreo.lexicon.api.LexiconLinkService lexiconLinkService(LexiconRepository repository) {
        return new com.lyreo.lexicon.api.LexiconLinkService(repository);
    }
    @Bean
    LexiconRepository lexiconRepository(NamedParameterJdbcTemplate jdbc,com.lyreo.platform.content.DocumentStore documents,tools.jackson.databind.ObjectMapper json) {
        return new JdbcLexiconRepository(jdbc,documents,json);
    }

    @Bean
    LexiconSearchService lexiconSearchService(LexiconRepository repository) {
        return new LexiconSearchService(repository);
    }
}
