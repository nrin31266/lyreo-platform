package com.lyreo.vocabulary.infrastructure;

import com.lyreo.entitlement.api.EntitlementService;
import com.lyreo.lexicon.api.LexiconLinkService;
import com.lyreo.platform.config.application.OperationIdempotencyService;
import com.lyreo.platform.content.AssetStore;
import com.lyreo.vocabulary.application.VocabularyCollectionService;
import com.lyreo.vocabulary.application.VocabularyCommandService;
import com.lyreo.vocabulary.application.VocabularyStudyService;
import com.lyreo.vocabulary.application.port.SpacedRepetitionScheduler;
import com.lyreo.vocabulary.application.port.VocabularyCollectionRepository;
import com.lyreo.vocabulary.application.port.VocabularyRepository;
import com.lyreo.vocabulary.application.port.VocabularyStudyRepository;
import com.lyreo.vocabulary.infrastructure.persistence.JdbcVocabularyCollectionRepository;
import com.lyreo.vocabulary.infrastructure.persistence.JdbcVocabularyRepository;
import com.lyreo.vocabulary.infrastructure.persistence.JdbcVocabularyStudyRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class VocabularyConfiguration {

    @Bean
    VocabularyRepository vocabularyRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        return new JdbcVocabularyRepository(jdbc, json);
    }

    @Bean
    VocabularyStudyRepository vocabularyStudyRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        return new JdbcVocabularyStudyRepository(jdbc, json);
    }

    @Bean
    VocabularyCollectionRepository vocabularyCollectionRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcVocabularyCollectionRepository(jdbc);
    }

    @Bean
    SpacedRepetitionScheduler spacedRepetitionScheduler() {
        return new StarterFsrsCompatibleScheduler();
    }

    @Bean
    VocabularyCommandService vocabularyCommandService(
        VocabularyRepository repository,
        SpacedRepetitionScheduler scheduler,
        ApplicationEventPublisher events,
        OperationIdempotencyService idempotency,
        EntitlementService entitlements,
        LexiconLinkService lexiconLinkService,
        VocabularyCollectionRepository collections,
        AssetStore assets,
        ObjectMapper json
    ) {
        return new VocabularyCommandService(
            repository,
            scheduler,
            events,
            idempotency,
            entitlements,
            lexiconLinkService,
            collections,
            assets,
            json
        );
    }

    @Bean
    VocabularyStudyService vocabularyStudyService(
        VocabularyStudyRepository repository,
        EntitlementService entitlements,
        AssetStore assets,
        OperationIdempotencyService idempotency,
        ApplicationEventPublisher events,
        ObjectMapper json,
        SpacedRepetitionScheduler scheduler
    ) {
        return new VocabularyStudyService(repository, entitlements, assets, idempotency, events, json, scheduler);
    }

    @Bean
    VocabularyCollectionService vocabularyCollectionService(VocabularyCollectionRepository repository) {
        return new VocabularyCollectionService(repository);
    }
}
