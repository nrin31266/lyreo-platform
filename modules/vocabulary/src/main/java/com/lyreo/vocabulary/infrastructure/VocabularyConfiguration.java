package com.lyreo.vocabulary.infrastructure;

import com.lyreo.vocabulary.application.SpacedRepetitionScheduler;
import com.lyreo.vocabulary.application.VocabularyCommandService;
import com.lyreo.vocabulary.application.VocabularyRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class VocabularyConfiguration {

    @Bean
    VocabularyRepository vocabularyRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcVocabularyRepository(jdbc);
    }

    @Bean
    SpacedRepetitionScheduler spacedRepetitionScheduler() {
        return new StarterFsrsCompatibleScheduler();
    }

    @Bean
    VocabularyCommandService vocabularyCommandService(
        VocabularyRepository repository,
        SpacedRepetitionScheduler scheduler,
        ApplicationEventPublisher events
    ) {
        return new VocabularyCommandService(repository, scheduler, events);
    }
}
