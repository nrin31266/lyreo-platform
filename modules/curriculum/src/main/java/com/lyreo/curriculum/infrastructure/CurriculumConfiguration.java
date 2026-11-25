package com.lyreo.curriculum.infrastructure;

import com.lyreo.curriculum.application.CurriculumAdminService;
import com.lyreo.curriculum.application.CurriculumService;
import com.lyreo.curriculum.application.port.CurriculumProgressRepository;
import com.lyreo.curriculum.infrastructure.persistence.JdbcCurriculumProgressRepository;
import com.lyreo.platform.config.application.ConsumerReceiptStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration
public class CurriculumConfiguration {

    @Bean
    CurriculumProgressRepository curriculumProgressRepository(
        NamedParameterJdbcTemplate jdbc
    ) {
        return new JdbcCurriculumProgressRepository(jdbc);
    }

    @Bean
    CurriculumService curriculumService(
        CurriculumProgressRepository repository,
        ObjectProvider<ConsumerReceiptStore> receiptStoreProvider,
        NamedParameterJdbcTemplate jdbc
    ) {
        ConsumerReceiptStore receiptStore = receiptStoreProvider.getIfAvailable(() ->
            (consumer, factId) -> jdbc.update(
                """
                INSERT INTO consumer_event_receipt(consumer, fact_id)
                VALUES (:consumer, :factId)
                ON CONFLICT (consumer, fact_id) DO NOTHING
                """,
                new MapSqlParameterSource()
                    .addValue("consumer", consumer)
                    .addValue("factId", factId)
            ) == 1
        );
        return new CurriculumService(repository, receiptStore);
    }

    @Bean
    CurriculumAdminService curriculumAdminService(
        CurriculumProgressRepository repository,
        CurriculumService curriculumService
    ) {
        return new CurriculumAdminService(repository, curriculumService);
    }
}
